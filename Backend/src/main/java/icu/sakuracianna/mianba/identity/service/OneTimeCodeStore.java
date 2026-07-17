package icu.sakuracianna.mianba.identity.service;

import java.time.Duration;

/** 带 TTL 且成功后原子消费的一次性验证码存储。 */
public interface OneTimeCodeStore {
    /**
     * 写入或替换指定邮箱的验证码。
     *
     * @param email 已规范化邮箱
     * @param code 待验证验证码
     * @param ttl 有效时长
     */
    void issue(String email, String code, Duration ttl);

    /**
     * 原子校验并删除验证码，确保同一验证码最多成功一次。
     *
     * @return 验证码存在且匹配时返回 {@code true}
     */
    boolean consume(String email, String code);

    /** 仅当当前值仍等于指定验证码时撤销，避免并发请求误删更新的验证码。 */
    void revoke(String email, String code);
}
