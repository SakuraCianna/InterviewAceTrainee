package icu.sakuracianna.mianba.identity.service;

/** 将验证码发送到已规范化邮箱的外部交付端口。 */
public interface VerificationCodeDelivery {
    /**
     * 交付验证码；实现不得记录或在异常中暴露验证码明文。
     *
     * @param expiresInSeconds 验证码剩余有效秒数
     */
    void deliver(String email, String code, int expiresInSeconds);
}
