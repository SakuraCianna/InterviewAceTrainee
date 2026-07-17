package icu.sakuracianna.mianba.identity.security;

import icu.sakuracianna.mianba.identity.service.SessionRegistry;
import icu.sakuracianna.mianba.identity.service.UserAccount;
import icu.sakuracianna.mianba.identity.service.UserAccountRepository;
import icu.sakuracianna.mianba.identity.web.AuthController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 从 Bearer 或安全 Cookie 读取 JWT，并联合 Redis 会话与数据库认证版本建立上下文。 */
public final class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String AUTH_TRANSPORT_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".transport";
    public static final String COOKIE_TRANSPORT = "cookie";
    public static final String BEARER_TRANSPORT = "bearer";

    private final JwtService jwt;
    private final SessionRegistry sessions;
    private final UserAccountRepository users;

    public JwtAuthenticationFilter(JwtService jwt, SessionRegistry sessions, UserAccountRepository users) {
        this.jwt = jwt;
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TokenCandidate candidate = token(request);
        if (candidate != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthenticatedUser principal = jwt.verify(candidate.token());
                UserAccount current = users.findById(principal.userId()).orElse(null);
                if (sessions.isActive(principal.userId(), principal.sessionId())
                        && matchesCurrentIdentity(principal, current)) {
                    request.setAttribute(AUTH_TRANSPORT_ATTRIBUTE, candidate.transport());
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal,
                            candidate.token(),
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().toUpperCase())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (RuntimeException ignored) {
                // 认证失败统一按匿名请求继续，不将 JWT、Redis 或账号状态差异暴露给攻击者。
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean matchesCurrentIdentity(AuthenticatedUser principal, UserAccount current) {
        return current != null
                && current.active()
                && current.authVersion() == principal.authVersion()
                && current.email().equals(principal.email())
                && current.role().equals(principal.role());
    }

    private static TokenCandidate token(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : new TokenCandidate(token, BEARER_TRANSPORT);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> AuthController.ACCESS_TOKEN_COOKIE.equals(cookie.getName()))
                .map(cookie -> new TokenCandidate(cookie.getValue(), COOKIE_TRANSPORT))
                .findFirst()
                .orElse(null);
    }

    private record TokenCandidate(String token, String transport) {
    }
}
