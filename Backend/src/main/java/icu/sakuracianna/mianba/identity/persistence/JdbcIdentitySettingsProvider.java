package icu.sakuracianna.mianba.identity.persistence;

import icu.sakuracianna.mianba.identity.service.IdentitySettings;
import icu.sakuracianna.mianba.identity.service.IdentitySettingsProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 从 PostgreSQL system_configs 读取身份业务设置，不缓存以保证管理员变更即时生效。 */
@Repository
public final class JdbcIdentitySettingsProvider implements IdentitySettingsProvider {
    private final JdbcTemplate jdbc;

    public JdbcIdentitySettingsProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IdentitySettings current() {
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query("""
                SELECT config_key, value_json::text AS value_json
                FROM system_configs
                WHERE config_key IN (?, ?, ?, ?, ?)
                """, (resultSet, rowNumber) -> Map.entry(
                resultSet.getString("config_key"), resultSet.getString("value_json")),
                IdentitySettings.REGISTRATION_OPEN,
                IdentitySettings.PASSWORD_LOGIN_ENABLED,
                IdentitySettings.EMAIL_CODE_LOGIN_ENABLED,
                IdentitySettings.NEW_USER_DEFAULT_CREDITS,
                IdentitySettings.NEW_USER_TRIAL_VOUCHERS).forEach(
                        entry -> values.put(entry.getKey(), entry.getValue()));
        return IdentitySettings.fromJsonScalars(values);
    }
}
