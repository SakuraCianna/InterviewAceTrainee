package icu.sakuracianna.mianba.identity.security;

import icu.sakuracianna.mianba.identity.web.AuthController;
import icu.sakuracianna.mianba.platform.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅对使用 Cookie 认证的非安全 HTTP 方法实施双提交 CSRF 校验。 */
public final class DoubleSubmitCsrfFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final DoubleSubmitCsrfVerifier verifier = new DoubleSubmitCsrfVerifier();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Object transport = request.getAttribute(JwtAuthenticationFilter.AUTH_TRANSPORT_ATTRIBUTE);
        if (!SAFE_METHODS.contains(request.getMethod())
                && JwtAuthenticationFilter.COOKIE_TRANSPORT.equals(transport)
                && !verifier.matches(cookie(request, AuthController.CSRF_TOKEN_COOKIE),
                        request.getHeader("X-CSRF-Token"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            response.getWriter().write("{\"detail\":\"csrf_validation_failed\","
                    + "\"message\":\"CSRF 校验失败\",\"request_id\":\""
                    + (requestId instanceof String value ? value : "req_unknown")
                    + "\",\"errors\":[]}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String cookie(HttpServletRequest request, String name) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
