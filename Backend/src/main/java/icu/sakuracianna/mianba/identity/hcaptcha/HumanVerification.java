package icu.sakuracianna.mianba.identity.hcaptcha;

/** 在高风险匿名身份入口验证请求确实来自完成人机挑战的客户端。 */
public interface HumanVerification {
    /**
     * 校验单次 hCaptcha 响应；关闭功能时直接返回。
     *
     * @param captchaToken 浏览器从 hCaptcha 获取的一次性响应
     * @param remoteIp 由受信反向代理链解析后的客户端地址
     */
    void verify(String captchaToken, String remoteIp);
}
