package icu.sakuracianna.mianba.identity.service;

import java.util.Optional;
import java.util.UUID;

/** 身份领域访问用户账号的持久化端口。 */
public interface UserAccountRepository {
    /** 按规范化邮箱查询账号，不获取数据库写锁。 */
    Optional<UserAccount> findByEmail(String email);

    /** 按规范化邮箱查询并锁定账号，供登录、改密等安全事务使用。 */
    Optional<UserAccount> findByEmailForUpdate(String email);

    /** 按标识查询账号，不获取数据库写锁。 */
    Optional<UserAccount> findById(UUID id);

    /** 按标识查询并锁定账号，防止并发安全操作相互覆盖。 */
    Optional<UserAccount> findByIdForUpdate(UUID id);

    /** 返回仍在有效期内的可用试用券次数总和。 */
    int availableVoucherUses(UUID userId);

    /** 创建账号；邮箱唯一冲突必须转换为稳定的业务错误。 */
    UserAccount create(String email, String passwordHash, int initialCredits);

    /** 更新密码哈希并提升认证版本，使已签发令牌立即失效。 */
    void updatePassword(UUID userId, String passwordHash);
}
