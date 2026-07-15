package icu.sakuracianna.mianba.identity.service;

import java.time.Duration;

/**
 * 对高成本或高风险身份接口实施原子限流。
 *
 * 调用方传入的主体可能包含邮箱或 IP，实现必须在写入外部存储前做不可逆摘要，
 * 避免限流键本身成为个人信息泄漏源。
 */
public interface AbuseProtection {

    /**
     * 消耗一个限流配额，超过窗口上限时抛出稳定的 API 异常。
     *
     * @param action 业务动作，不得包含用户输入
     * @param subject 被限流主体，例如规范化邮箱或客户端 IP
     * @param limit 窗口内最大请求数
     * @param window 统计窗口
     */
    void check(String action, String subject, int limit, Duration window);
}
