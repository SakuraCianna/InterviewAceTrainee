package icu.sakuracianna.mianba.identity.config;

import icu.sakuracianna.mianba.identity.security.DoubleSubmitCsrfFilter;
import icu.sakuracianna.mianba.identity.security.JwtAuthenticationFilter;
import icu.sakuracianna.mianba.identity.security.JwtService;
import icu.sakuracianna.mianba.identity.service.SessionRegistry;
import icu.sakuracianna.mianba.identity.service.UserAccountRepository;
import icu.sakuracianna.mianba.platform.config.SecurityProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** 配置无状态 JWT、Cookie CSRF、显式 CORS 与默认拒绝的 API 授权规则。 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class SecurityConfiguration {

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwt,
            SessionRegistry sessions,
            UserAccountRepository users) {
        return new JwtAuthenticationFilter(jwt, sessions, users);
    }

    @Bean
    DoubleSubmitCsrfFilter doubleSubmitCsrfFilter() {
        return new DoubleSubmitCsrfFilter();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            DoubleSubmitCsrfFilter csrfFilter) throws Exception {
        http
                .cors(cors -> { })
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/api/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/hcaptcha/config").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/interview-products/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/password/register",
                                "/api/auth/password/login",
                                "/api/auth/password/reset",
                                "/api/auth/email-code/request",
                                "/api/auth/email-code/login",
                                "/api/auth/admin/login").permitAll()
                        .requestMatchers("/api/admin/**", "/api/ai-providers/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                "X-CSRF-Token", "X-Request-ID"));
        configuration.setExposedHeaders(List.of("Location", "Retry-After", "X-Request-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
