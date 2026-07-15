package icu.sakuracianna.mianba.identity.service;

import java.util.Map;

/** 影响注册与用户登录的强类型系统配置快照。 */
public record IdentitySettings(
        boolean registrationOpen,
        boolean passwordLoginEnabled,
        boolean emailCodeLoginEnabled,
        int newUserDefaultCredits,
        int newUserTrialVouchers) {

    public static final String REGISTRATION_OPEN = "registration_open";
    public static final String PASSWORD_LOGIN_ENABLED = "password_login_enabled";
    public static final String EMAIL_CODE_LOGIN_ENABLED = "email_code_login_enabled";
    public static final String NEW_USER_DEFAULT_CREDITS = "new_user_default_credits";
    public static final String NEW_USER_TRIAL_VOUCHERS = "new_user_trial_vouchers";

    public IdentitySettings {
        requireRange(NEW_USER_DEFAULT_CREDITS, newUserDefaultCredits, 0, 1_000);
        requireRange(NEW_USER_TRIAL_VOUCHERS, newUserTrialVouchers, 0, 20);
    }

    /** 从 PostgreSQL jsonb 标量文本读取完整配置；缺项或类型损坏时拒绝静默降级。 */
    public static IdentitySettings fromJsonScalars(Map<String, String> values) {
        return new IdentitySettings(
                booleanValue(values, REGISTRATION_OPEN),
                booleanValue(values, PASSWORD_LOGIN_ENABLED),
                booleanValue(values, EMAIL_CODE_LOGIN_ENABLED),
                integerValue(values, NEW_USER_DEFAULT_CREDITS),
                integerValue(values, NEW_USER_TRIAL_VOUCHERS));
    }

    /** 校验管理员写入的配置值并把整数统一为 Integer。 */
    public static Object normalizeConfigValue(String key, Object value) {
        return switch (key) {
            case REGISTRATION_OPEN, PASSWORD_LOGIN_ENABLED, EMAIL_CODE_LOGIN_ENABLED -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException(key + " must be a boolean");
                }
                yield value;
            }
            case NEW_USER_DEFAULT_CREDITS -> integerConfig(key, value, 0, 1_000);
            case NEW_USER_TRIAL_VOUCHERS -> integerConfig(key, value, 0, 20);
            default -> throw new IllegalArgumentException("Unsupported identity setting: " + key);
        };
    }

    private static boolean booleanValue(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalStateException(key + " must be a JSON boolean");
        }
        return Boolean.parseBoolean(value);
    }

    private static int integerValue(Map<String, String> values, String key) {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(key + " must be a JSON integer", exception);
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing system config: " + key);
        }
        return value;
    }

    private static int integerConfig(String key, Object value, int minimum, int maximum) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                || decimal < minimum || decimal > maximum) {
            throw new IllegalArgumentException(key + " is outside the allowed range");
        }
        return (int) decimal;
    }

    private static void requireRange(String key, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(key + " is outside the allowed range");
        }
    }
}
