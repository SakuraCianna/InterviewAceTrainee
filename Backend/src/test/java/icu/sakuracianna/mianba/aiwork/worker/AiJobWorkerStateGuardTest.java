package icu.sakuracianna.mianba.aiwork.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import icu.sakuracianna.mianba.aiwork.messaging.AiJobEnvelope;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class AiJobWorkerStateGuardTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @Test
    void staleSessionIsRejectedByTheLastDatabaseReadBeforeProviderCall() throws Exception {
        WorkerFixture fixture = new WorkerFixture();
        fixture.rejectInputOnlyWhenStateGuardIsPresent = true;

        fixture.worker.consume(fixture.message(), fixture.channel);

        verify(fixture.generator, never()).evaluate(any());
        assertThat(fixture.jdbc.terminalStatusArguments())
                .containsExactly("CANCELLED", "TASK_STALE");
        verify(fixture.channel).basicAck(41L, false);
    }

    @Test
    void taskStaleFailureIsCancelledWithoutRestoringInterviewContext() throws Exception {
        WorkerFixture fixture = new WorkerFixture();
        when(fixture.generator.evaluate(any()))
                .thenThrow(new AiWorkerException("TASK_STALE", "任务对应的面试轮次已变化", false));

        fixture.worker.consume(fixture.message(), fixture.channel);

        assertThat(fixture.jdbc.terminalStatusArguments())
                .containsExactly("CANCELLED", "TASK_STALE");
        assertThat(fixture.jdbc.sqlCalls())
                .noneMatch(call -> call.sql().contains("UPDATE sessions SET status = 'active'"));
        verify(fixture.channel).basicAck(41L, false);
    }

    @Test
    void failurePersistenceOutageRequeuesWithoutAcknowledgingTheMessage() throws Exception {
        WorkerFixture fixture = new WorkerFixture();
        fixture.jdbc.failFailurePersistence = true;
        when(fixture.generator.evaluate(any()))
                .thenThrow(new AiWorkerException("AI_INPUT_INVALID", "任务输入不完整", false));

        assertThatCode(() -> fixture.worker.consume(fixture.message(), fixture.channel))
                .doesNotThrowAnyException();

        verify(fixture.channel).basicNack(41L, false, true);
        verify(fixture.channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void turnIsRestoredOnlyWhenAwaitingSessionWasActuallyReactivated() {
        WorkerFixture fixture = new WorkerFixture();
        fixture.jdbc.restoreSessionResult = 0;

        ReflectionTestUtils.invokeMethod(
                fixture.worker, "restoreInterviewContext", fixture.sessionId);

        assertThat(fixture.jdbc.sqlCalls())
                .noneMatch(call -> call.sql().contains("UPDATE turns SET status = 'waiting_answer'"));
    }

    @Test
    void providerResultCannotAdvanceSessionThatExpiredDuringExternalCall() throws Exception {
        WorkerFixture fixture = new WorkerFixture();
        fixture.completionSessionExpiresAt = NOW.minusSeconds(1);
        when(fixture.generator.evaluate(any()))
                .thenReturn(new InterviewEvaluation(82, "反馈", "追问", "下一题"));

        fixture.worker.consume(fixture.message(), fixture.channel);

        assertThat(fixture.jdbc.sqlCalls())
                .noneMatch(call -> call.sql().contains("SET status = 'answered'"));
        assertThat(fixture.jdbc.sqlCalls())
                .anyMatch(call -> call.sql().contains("SET status = 'CANCELLED'")
                        && call.sql().contains("TASK_STALE"));
        verify(fixture.channel).basicAck(41L, false);
    }

    @Test
    void providerResultAfterJobDeadlineIsDiscardedAndLiveSessionIsRestored() throws Exception {
        WorkerFixture fixture = new WorkerFixture();
        fixture.claimExpiresAt = NOW.plusSeconds(120);
        when(fixture.generator.evaluate(any())).thenAnswer(invocation -> {
            fixture.clock.advance(Duration.ofSeconds(121));
            return new InterviewEvaluation(82, "反馈", "追问", "下一题");
        });

        fixture.worker.consume(fixture.message(), fixture.channel);

        assertThat(fixture.jdbc.sqlCalls())
                .noneMatch(call -> call.sql().contains("SET status = 'answered'"));
        assertThat(fixture.jdbc.sqlCalls())
                .anyMatch(call -> call.sql().contains("stage = 'TASK_EXPIRED'")
                        && call.sql().contains("error_code = 'TASK_EXPIRED'"));
        assertThat(fixture.jdbc.sqlCalls())
                .anyMatch(call -> call.sql().contains("UPDATE sessions SET status = 'active'"));
        verify(fixture.channel).basicAck(41L, false);
    }

    private static final class WorkerFixture {
        private final UUID jobId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final AiJobEnvelope envelope = AiJobEnvelope.create(jobId, "test", "test", NOW);
        private final RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(jobId, sessionId);
        private final ObjectMapper mapper = mock(ObjectMapper.class);
        private final InterviewAiGenerator generator = mock(InterviewAiGenerator.class);
        private final Channel channel = mock(Channel.class);
        private final MutableClock clock = new MutableClock(NOW);
        private final AiJobWorker worker;
        private boolean rejectInputOnlyWhenStateGuardIsPresent;
        private Instant completionSessionExpiresAt = NOW.plusSeconds(3600);
        private Instant claimExpiresAt = NOW.plusSeconds(1800);

        private WorkerFixture() {
            jdbc.fixture = this;
            try {
                when(mapper.readValue(any(byte[].class), eq(AiJobEnvelope.class))).thenReturn(envelope);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            worker = new AiJobWorker(
                    jdbc,
                    immediateTransactions(),
                    mapper,
                    generator,
                    clock);
        }

        private Message message() {
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryTag(41L);
            properties.setMessageId(envelope.messageId().toString());
            return new Message(new byte[] {1}, properties);
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final UUID jobId;
        private final UUID sessionId;
        private final List<SqlCall> calls = new ArrayList<>();
        private WorkerFixture fixture;
        private int restoreSessionResult = 1;
        private boolean failFailurePersistence;

        private RecordingJdbcTemplate(UUID jobId, UUID sessionId) {
            this.jobId = jobId;
            this.sessionId = sessionId;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("processed_messages")) {
                return requiredType.cast(0L);
            }
            return null;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            try {
                if (sql.contains("RETURNING session_id")) {
                    return List.of();
                }
                if (sql.contains("RETURNING id, session_id, attempt")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getObject("id", UUID.class)).thenReturn(jobId);
                    when(resultSet.getObject("session_id", UUID.class)).thenReturn(sessionId);
                    when(resultSet.getInt("attempt")).thenReturn(1);
                    when(resultSet.getInt("max_attempts")).thenReturn(3);
                    when(resultSet.getLong("version")).thenReturn(1L);
                    when(resultSet.getTimestamp("expires_at"))
                            .thenReturn(Timestamp.from(fixture.claimExpiresAt));
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                if (sql.contains("FROM providers")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                    when(resultSet.getString("provider_type")).thenReturn("llm");
                    when(resultSet.getString("provider_name")).thenReturn("deepseek");
                    when(resultSet.getString("model_name")).thenReturn("deepseek-chat");
                    when(resultSet.getString("purpose")).thenReturn("interview");
                    when(resultSet.getBoolean("enabled")).thenReturn(true);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                if (sql.contains("FROM ai_jobs j") && sql.contains("JOIN sessions s")) {
                    boolean guarded = sql.contains("j.version = ?")
                            && sql.contains("j.lease_until > ?")
                            && sql.contains("j.expires_at > ?")
                            && sql.contains("s.expires_at > ?");
                    if (fixture.rejectInputOnlyWhenStateGuardIsPresent && guarded) {
                        return List.of();
                    }
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("interview_type")).thenReturn("job");
                    when(resultSet.getString("round_name")).thenReturn("岗位开场");
                    when(resultSet.getString("question_text")).thenReturn("问题");
                    when(resultSet.getString("answer_text")).thenReturn("回答");
                    when(resultSet.getInt("current_turn_index")).thenReturn(0);
                    when(resultSet.getInt("total_turns")).thenReturn(2);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                if (sql.contains("SELECT question_text") && sql.contains("turn_index <")) {
                    return List.of();
                }
                if (sql.contains("SELECT id FROM ai_jobs")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getObject("id", UUID.class)).thenReturn(jobId);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                if (sql.contains("SELECT status, lease_owner, version")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("status")).thenReturn("RUNNING");
                    when(resultSet.getString("lease_owner")).thenReturn(workerId(fixture.worker));
                    when(resultSet.getLong("version")).thenReturn(1L);
                    when(resultSet.getTimestamp("lease_until"))
                            .thenReturn(Timestamp.from(NOW.plusSeconds(120)));
                    when(resultSet.getTimestamp("expires_at"))
                            .thenReturn(Timestamp.from(fixture.claimExpiresAt));
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                if (sql.contains("SELECT status, current_turn_index, total_turns")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("status")).thenReturn("awaiting_ai");
                    when(resultSet.getInt("current_turn_index")).thenReturn(0);
                    when(resultSet.getInt("total_turns")).thenReturn(2);
                    when(resultSet.getTimestamp("expires_at"))
                            .thenReturn(Timestamp.from(fixture.completionSessionExpiresAt));
                    return List.of(rowMapper.mapRow(resultSet, 0));
                }
                return List.of();
            } catch (Exception exception) {
                throw new DataAccessResourceFailureException("Unable to create JDBC test row", exception);
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return query(sql, rowMapper, new Object[0]);
        }

        @Override
        public int update(String sql, Object... args) {
            calls.add(new SqlCall(sql, java.util.Arrays.asList(args.clone())));
            if (failFailurePersistence && sql.contains("SET status = ?, stage = ?")) {
                throw new DataAccessResourceFailureException("Simulated failure-state persistence outage");
            }
            if (sql.contains("UPDATE sessions SET status = 'active'")) {
                return restoreSessionResult;
            }
            return 1;
        }

        private List<String> terminalStatusArguments() {
            return calls.stream()
                    .filter(call -> call.sql().contains("SET status = ?, stage = ?"))
                    .findFirst()
                    .map(call -> List.of(
                            String.valueOf(call.arguments().get(0)),
                            String.valueOf(call.arguments().get(1))))
                    .orElseGet(List::of);
        }

        private List<SqlCall> sqlCalls() {
            return List.copyOf(calls);
        }
    }

    private static String workerId(AiJobWorker worker) {
        return (String) ReflectionTestUtils.getField(worker, "workerId");
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
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
        });
    }

    private record SqlCall(String sql, List<Object> arguments) {
    }
}
