package icu.sakuracianna.mianba.identity.service;

import java.util.UUID;

/** 记录不含凭据的认证安全事件，供管理员审计和异常检测使用。 */
public interface AuthAttemptRecorder {

    /**
     * 写入一次认证结果。
     *
     * @param userId 成功识别出的用户，未知时为 {@code null}
     * @param email 已规范化的登录邮箱
     * @param method password、email_code、admin_password_code、register 或 password_reset
     * @param role 目标入口角色
     * @param success 是否认证成功
     * @param failureReason 稳定错误码，成功时为 {@code null}
     * @param ipAddress 客户端地址
     * @param userAgent 浏览器标识，已限制长度
     * @param requestId 请求关联 ID
     */
    void record(
            UUID userId,
            String email,
            String method,
            String role,
            boolean success,
            String failureReason,
            String ipAddress,
            String userAgent,
            String requestId);
}
