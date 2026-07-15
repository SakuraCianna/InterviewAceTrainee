package icu.sakuracianna.mianba.aiwork.messaging;

/** AI 任务主队列、Exchange 和死信队列的稳定命名；延迟重试由数据库 Outbox 调度。 */
public final class AiMessagingTopology {
    public static final String EXCHANGE = "mianba.ai.v1";
    public static final String ROUTING_KEY = "mianba.ai.job.v1";
    public static final String JOB_QUEUE = "mianba.ai.jobs.v1";
    public static final String DEAD_LETTER_EXCHANGE = "mianba.ai.dlx.v1";
    public static final String DEAD_LETTER_ROUTING_KEY = "mianba.ai.dead.v1";
    public static final String DEAD_LETTER_QUEUE = "mianba.ai.dlq.v1";

    private AiMessagingTopology() {
    }
}
