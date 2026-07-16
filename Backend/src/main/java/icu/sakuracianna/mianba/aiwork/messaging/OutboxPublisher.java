package icu.sakuracianna.mianba.aiwork.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 定时发布已提交的 Outbox 事件，并在 RabbitMQ publisher confirm 成功后标记已发布。
 *
 * RabbitMQ 确认成功前不得更新 {@code published_at}，否则确认丢失会造成任务永久漏投。
 * 发送失败的事件保留在表中退避重试，因此消息遵循至少一次投递语义，Worker 必须依据消息 ID
 * 与任务版本消除重复副作用。
 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;
    private final String publisherId = "api-outbox-" + UUID.randomUUID();

    public OutboxPublisher(
            JdbcTemplate jdbc,
            RabbitTemplate rabbit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 每轮最多发布十条可用事件，防止调度线程长期占用 CPU。
     * 领取事务在网络发送前结束，避免等待 RabbitMQ 确认期间持有数据库行锁。
     */
    @Scheduled(fixedDelayString = "${mianba.outbox.poll-delay-ms:750}")
    public void publishAvailable() {
        for (int published = 0; published < 10; published++) {
            OutboxRow row = transactions.execute(status -> claimOne());
            if (row == null) {
                return;
            }
            publishOne(row);
        }
    }

    private OutboxRow claimOne() {
        List<OutboxRow> rows = jdbc.query("""
                SELECT o.id, o.aggregate_id, o.payload::text AS payload, o.publish_attempts,
                       j.status AS job_status
                FROM outbox_events o
                JOIN ai_jobs j ON j.id = o.aggregate_id
                WHERE o.published_at IS NULL AND o.available_at <= now()
                  AND (o.claim_until IS NULL OR o.claim_until <= now())
                ORDER BY o.created_at, o.id
                LIMIT 1
                FOR UPDATE OF o SKIP LOCKED
                """, (rs, row) -> new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("payload"),
                rs.getInt("publish_attempts"),
                rs.getString("job_status")));
        if (rows.isEmpty()) {
            return null;
        }
        OutboxRow row = rows.getFirst();
        int claimed = jdbc.update("""
                UPDATE outbox_events
                SET claim_owner = ?, claim_until = now() + interval '30 seconds'
                WHERE id = ? AND published_at IS NULL
                  AND (claim_until IS NULL OR claim_until <= now())
                """, publisherId, row.id());
        if (claimed != 1) {
            throw new IllegalStateException("Outbox event claim was lost before publishing");
        }
        if ("RETRYING".equals(row.jobStatus())) {
            // QUEUED 是无错误态；错误字段必须在同一事务清空，否则会违反 ai_jobs 状态约束。
            int queued = jdbc.update("""
                    UPDATE ai_jobs
                    SET status = 'QUEUED', stage = 'WAITING_FOR_WORKER',
                        error_code = NULL, error_message = NULL,
                        next_attempt_at = NULL, version = version + 1, updated_at = now()
                    WHERE id = ? AND status = 'RETRYING'
                    """, row.jobId());
            if (queued != 1) {
                throw new IllegalStateException("Unable to return retrying AI job to the queue");
            }
        }
        return row;
    }

    private void publishOne(OutboxRow row) {
        if ("CANCELLED".equals(row.jobStatus()) || "SUCCEEDED".equals(row.jobStatus())) {
            transactions.executeWithoutResult(status -> markPublished(row.id()));
            return;
        }
        try {
            Message message = MessageBuilder
                    .withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                    .setContentType("application/json")
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(row.id().toString())
                    .build();
            CorrelationData correlation = new CorrelationData(row.id().toString());
            rabbit.send(AiMessagingTopology.EXCHANGE, AiMessagingTopology.ROUTING_KEY, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ publisher NACK: " + confirm.reason());
            }
            transactions.executeWithoutResult(status -> markPublished(row.id()));
        } catch (Exception exception) {
            int attempts = row.publishAttempts() + 1;
            long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
            transactions.executeWithoutResult(status -> jdbc.update("""
                        UPDATE outbox_events
                        SET publish_attempts = publish_attempts + 1,
                            available_at = now() + (? * interval '1 second'),
                            claim_owner = NULL, claim_until = NULL,
                            last_error = ?
                        WHERE id = ? AND claim_owner = ?
                        """, delaySeconds, safeMessage(exception), row.id(), publisherId));
            LOGGER.warn("Outbox publish failed event_id={} attempt={}", row.id(), attempts);
        }
    }

    private void markPublished(UUID eventId) {
        int changed = jdbc.update("""
                UPDATE outbox_events
                SET published_at = now(), publish_attempts = publish_attempts + 1,
                    claim_owner = NULL, claim_until = NULL, last_error = NULL
                WHERE id = ? AND claim_owner = ?
                """, eventId, publisherId);
        if (changed != 1) {
            LOGGER.warn("Outbox claim was lost before completion event_id={}", eventId);
        }
    }

    private static String safeMessage(Exception exception) {
        String value = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record OutboxRow(UUID id, UUID jobId, String payload, int publishAttempts, String jobStatus) {
    }
}
