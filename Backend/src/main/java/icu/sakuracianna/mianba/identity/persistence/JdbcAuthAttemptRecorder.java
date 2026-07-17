package icu.sakuracianna.mianba.identity.persistence;

import icu.sakuracianna.mianba.identity.service.AuthAttemptRecorder;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL 认证审计写入器；审计失败不改变原始认证结果。 */
public final class JdbcAuthAttemptRecorder implements AuthAttemptRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcAuthAttemptRecorder.class);

    private final JdbcClient jdbc;

    public JdbcAuthAttemptRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(
            UUID userId,
            String email,
            String method,
            String role,
            boolean success,
            String failureReason,
            String ipAddress,
            String userAgent,
            String requestId) {
        try {
            jdbc.sql("""
                    INSERT INTO auth_login(
                        user_id, email, auth_method, role, success, failure_reason,
                        ip_address, user_agent, request_id)
                    VALUES (:userId, :email, :method, :role, :success, :failureReason,
                            NULLIF(:ipAddress, '')::inet, :userAgent, :requestId)
                    """)
                    .param("userId", userId)
                    .param("email", email)
                    .param("method", method)
                    .param("role", role)
                    .param("success", success)
                    .param("failureReason", failureReason)
                    .param("ipAddress", safeIp(ipAddress))
                    .param("userAgent", truncate(userAgent, 512))
                    .param("requestId", truncate(requestId, 96))
                    .update();
        } catch (RuntimeException exception) {
            // 审计是旁路能力，写入故障不得覆盖“密码错误”等原始业务结果。
            LOGGER.warn("Unable to persist auth audit request_id={}", truncate(requestId, 96), exception);
        }
    }

    private static String safeIp(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace("[", "").replace("]", "");
        int zone = normalized.indexOf('%');
        if (zone >= 0) {
            normalized = normalized.substring(0, zone);
        }
        return normalized.matches("[0-9A-Fa-f:.]{1,64}") ? normalized : "";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
