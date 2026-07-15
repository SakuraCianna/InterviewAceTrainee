package icu.sakuracianna.mianba.identity.service;

/** 密码哈希算法端口，禁止保存或返回原始密码。 */
public interface PasswordHasher {
    /**
     * 使用随机盐生成不可逆密码哈希。
     *
     * @param rawPassword 原始密码，仅可在调用期间驻留内存
     * @return 可持久化的自描述哈希
     */
    String hash(String rawPassword);

    /** 使用编码串内的参数校验原始密码，不得通过提前返回泄漏比较位置。 */
    boolean matches(String rawPassword, String encodedPassword);
}
