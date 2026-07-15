package icu.sakuracianna.mianba.interview.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class SessionDeletionCoordinatorTest {

    @Test
    void deleteMarksSessionBeforeRunningRecoverableErasureStages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = mock(SessionContentEraser.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(jdbc.query(
                argThat(sql -> sql.contains("SELECT status, content_erased_at")),
                org.mockito.ArgumentMatchers.<RowMapper<SessionDeletionCoordinator.SessionDeletionState>>any(),
                eq(sessionId),
                eq(userId)))
                .thenReturn(List.of(new SessionDeletionCoordinator.SessionDeletionState("active", null)));
        when(jdbc.update(
                argThat(sql -> sql.contains("SET status = 'deleting'")), eq(sessionId), eq(userId)))
                .thenReturn(1);
        when(jdbc.update(
                argThat(sql -> sql.contains("SET status = 'deleted', content_erased_at = now()")),
                eq(sessionId)))
                .thenReturn(1);

        SessionDeletionCoordinator coordinator = new SessionDeletionCoordinator(
                jdbc, immediateTransactions(), eraser);
        coordinator.delete(userId, sessionId);

        InOrder order = inOrder(jdbc, eraser);
        order.verify(jdbc).update(
                argThat(sql -> sql.contains("SET status = 'deleting'")), eq(sessionId), eq(userId));
        order.verify(eraser).eraseJobs(sessionId, "SESSION_DELETED");
        order.verify(eraser).eraseConversation(sessionId);
        order.verify(jdbc).update(
                argThat(sql -> sql.contains("SET status = 'deleted', content_erased_at = now()")),
                eq(sessionId));
    }

    @Test
    void alreadyErasedDeletionIsIdempotent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = mock(SessionContentEraser.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(jdbc.query(
                argThat(sql -> sql.contains("SELECT status, content_erased_at")),
                org.mockito.ArgumentMatchers.<RowMapper<SessionDeletionCoordinator.SessionDeletionState>>any(),
                eq(sessionId),
                eq(userId)))
                .thenReturn(List.of(new SessionDeletionCoordinator.SessionDeletionState(
                        "deleted", Timestamp.from(Instant.now()))));

        new SessionDeletionCoordinator(jdbc, immediateTransactions(), eraser)
                .delete(userId, sessionId);

        verify(eraser, never()).eraseJobs(any(), any());
        verify(eraser, never()).eraseConversation(any());
    }

    @Test
    void failedErasureLeavesDeletingMarkerForScheduledRecovery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = mock(SessionContentEraser.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(jdbc.query(
                argThat(sql -> sql.contains("SELECT status, content_erased_at")),
                org.mockito.ArgumentMatchers.<RowMapper<SessionDeletionCoordinator.SessionDeletionState>>any(),
                eq(sessionId),
                eq(userId)))
                .thenReturn(List.of(new SessionDeletionCoordinator.SessionDeletionState("active", null)));
        when(jdbc.update(
                argThat(sql -> sql.contains("SET status = 'deleting'")), eq(sessionId), eq(userId)))
                .thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(eraser).eraseJobs(sessionId, "SESSION_DELETED");

        SessionDeletionCoordinator coordinator = new SessionDeletionCoordinator(
                jdbc, immediateTransactions(), eraser);

        assertThatThrownBy(() -> coordinator.delete(userId, sessionId))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc).update(
                argThat(sql -> sql.contains("SET status = 'deleting'")), eq(sessionId), eq(userId));
        verify(jdbc, never()).update(
                argThat(sql -> sql.contains("SET status = 'deleted', content_erased_at = now()")),
                eq(sessionId));
    }

    @Test
    void resumeDeletionIgnoresSessionThatIsNotDeleting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = mock(SessionContentEraser.class);
        UUID sessionId = UUID.randomUUID();
        when(jdbc.query(
                argThat(sql -> sql.contains("SELECT status, content_erased_at")
                        && !sql.contains("user_id")),
                org.mockito.ArgumentMatchers.<RowMapper<SessionDeletionCoordinator.SessionDeletionState>>any(),
                eq(sessionId)))
                .thenReturn(List.of(new SessionDeletionCoordinator.SessionDeletionState("active", null)));

        new SessionDeletionCoordinator(jdbc, immediateTransactions(), eraser)
                .resumeDeletion(sessionId);

        verify(eraser, never()).eraseJobs(any(), any());
        verify(eraser, never()).eraseConversation(any());
    }

    @Test
    void retainedContentErasureChecksTerminalStateAndRetentionAge() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = mock(SessionContentEraser.class);
        UUID sessionId = UUID.randomUUID();
        when(jdbc.queryForObject(
                argThat(sql -> sql.contains("ended_at <= now() - interval '90 days'")),
                eq(Boolean.class),
                eq(sessionId)))
                .thenReturn(false);

        new SessionDeletionCoordinator(jdbc, immediateTransactions(), eraser)
                .eraseRetainedContent(sessionId);

        verify(eraser, never()).eraseJobs(any(), any());
        verify(eraser, never()).eraseConversation(any());
    }

    @Test
    void deletionFailsIfFinalStateCannotBeRecorded() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionContentEraser eraser = mock(SessionContentEraser.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(jdbc.query(
                argThat(sql -> sql.contains("SELECT status, content_erased_at")),
                org.mockito.ArgumentMatchers.<RowMapper<SessionDeletionCoordinator.SessionDeletionState>>any(),
                eq(sessionId),
                eq(userId)))
                .thenReturn(List.of(new SessionDeletionCoordinator.SessionDeletionState("active", null)));
        when(jdbc.update(
                argThat(sql -> sql.contains("SET status = 'deleting'")), eq(sessionId), eq(userId)))
                .thenReturn(1);
        when(jdbc.update(
                argThat(sql -> sql.contains("SET status = 'deleted', content_erased_at = now()")),
                eq(sessionId)))
                .thenReturn(0);
        when(jdbc.queryForObject(
                argThat(sql -> sql.contains("status = 'deleted'")
                        && sql.contains("content_erased_at IS NOT NULL")),
                eq(Boolean.class),
                eq(sessionId)))
                .thenReturn(false);

        SessionDeletionCoordinator coordinator = new SessionDeletionCoordinator(
                jdbc, immediateTransactions(), eraser);

        assertThatThrownBy(() -> coordinator.delete(userId, sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("final state");
    }

    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // 测试事务立即提交，不需要资源清理。
            }

            @Override
            public void rollback(TransactionStatus status) {
                // 测试事务没有外部资源，回滚由 mock 行为表达。
            }
        });
    }
}
