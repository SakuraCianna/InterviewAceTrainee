package icu.sakuracianna.mianba.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 同一 JAR 的运行角色与本地降级开关。 */
@ConfigurationProperties("mianba.runtime")
public record RuntimeProperties(
        String role,
        boolean production,
        boolean localFallbackEnabled) {

    public RuntimeProperties {
        role = role == null || role.isBlank() ? "api" : role.trim().toLowerCase();
        if (!role.equals("api") && !role.equals("worker") && !role.equals("migrate")) {
            throw new IllegalArgumentException("mianba.runtime.role must be api, worker or migrate");
        }
    }
}
