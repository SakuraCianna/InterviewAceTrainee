package icu.sakuracianna.mianba.aiwork.worker;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发布 Worker 就绪心跳；只有 Rabbit 通道和全部监听容器都可用时才标记 ready。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "worker")
public class WorkerHeartbeat {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerHeartbeat.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final RabbitListenerEndpointRegistry listeners;
    private final String instanceId = "worker-" + UUID.randomUUID();
    private final AtomicReference<Boolean> lastReady = new AtomicReference<>();

    public WorkerHeartbeat(
            JdbcTemplate jdbc,
            RabbitTemplate rabbit,
            RabbitListenerEndpointRegistry listeners) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.listeners = listeners;
    }

    /** 每十秒验证消费者和 broker，并将结果写入 API 可读的最小心跳表。 */
    @Scheduled(initialDelayString = "${mianba.worker.heartbeat-initial-delay-ms:2000}",
            fixedDelayString = "${mianba.worker.heartbeat-delay-ms:10000}")
    public void publish() {
        boolean consumersReady = !listeners.getListenerContainers().isEmpty()
                && listeners.getListenerContainers().stream().allMatch(container -> container.isRunning());
        boolean rabbitReady = false;
        try {
            rabbitReady = Boolean.TRUE.equals(rabbit.execute(channel -> channel.isOpen()));
        } catch (RuntimeException exception) {
            LOGGER.debug("Worker Rabbit heartbeat probe failed", exception);
        }
        boolean ready = consumersReady && rabbitReady;
        jdbc.update("""
                INSERT INTO runtime_heartbeats(
                    role, instance_id, consumers_ready, rabbit_ready, updated_at)
                VALUES ('worker', ?, ?, ?, now())
                ON CONFLICT (role) DO UPDATE
                SET instance_id = EXCLUDED.instance_id,
                    consumers_ready = EXCLUDED.consumers_ready,
                    rabbit_ready = EXCLUDED.rabbit_ready,
                    updated_at = now()
                """, instanceId, consumersReady, rabbitReady);
        Boolean previous = lastReady.getAndSet(ready);
        if (previous == null || previous != ready) {
            LOGGER.info("AI Worker readiness changed ready={} consumers={} rabbit={}",
                    ready, consumersReady, rabbitReady);
        }
    }
}
