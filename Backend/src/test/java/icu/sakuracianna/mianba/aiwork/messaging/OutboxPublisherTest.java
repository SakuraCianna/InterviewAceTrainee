package icu.sakuracianna.mianba.aiwork.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPublisherTest {

    @Test
    void retryClaimClearsFailureDetailsBeforeReturningJobToQueue() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        OutboxPublisher publisher = publisher(jdbc);

        Object row = ReflectionTestUtils.invokeMethod(publisher, "claimOne");

        assertThat(row).isNotNull();
        assertThat(jdbc.jobUpdateSql())
                .contains("status = 'QUEUED'")
                .contains("error_code = NULL")
                .contains("error_message = NULL");
    }

    @Test
    void retryClaimFailsWhenTheJobWasNotAtomicallyReturnedToQueue() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.jobUpdateResult = 0;
        OutboxPublisher publisher = publisher(jdbc);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(publisher, "claimOne"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retrying AI job");
    }

    private static OutboxPublisher publisher(JdbcTemplate jdbc) {
        return new OutboxPublisher(jdbc, mock(RabbitTemplate.class), immediateTransactions());
    }

    private static PlatformTransactionManager immediateTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // 测试事务没有外部资源，调用即视为提交。
            }

            @Override
            public void rollback(TransactionStatus status) {
                // 测试替身不持有外部资源，无需执行回滚清理。
            }
        };
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final UUID eventId = UUID.randomUUID();
        private final UUID jobId = UUID.randomUUID();
        private final List<String> updates = new ArrayList<>();
        private int jobUpdateResult = 1;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            try {
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getObject("id", UUID.class)).thenReturn(eventId);
                when(resultSet.getObject("aggregate_id", UUID.class)).thenReturn(jobId);
                when(resultSet.getString("payload")).thenReturn("{}");
                when(resultSet.getInt("publish_attempts")).thenReturn(1);
                when(resultSet.getString("job_status")).thenReturn("RETRYING");
                return List.of(rowMapper.mapRow(resultSet, 0));
            } catch (SQLException exception) {
                throw new DataAccessResourceFailureException("Unable to create outbox test row", exception);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return sql.contains("UPDATE ai_jobs") ? jobUpdateResult : 1;
        }

        private String jobUpdateSql() {
            return updates.stream()
                    .filter(sql -> sql.contains("UPDATE ai_jobs"))
                    .findFirst()
                    .orElse("");
        }
    }
}
