package icu.sakuracianna.mianba.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.identity.service.SessionRegistry;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class AdminServiceTest {

    @Test
    void statusReasonParticipatesInIdempotencyAndReplaySkipsMutation() {
        String expectedHash = operationHash("user@example.com", false, "manual_disable");
        ReplayJdbcTemplate jdbc = new ReplayJdbcTemplate(
                expectedHash, "{\"email\":\"user@example.com\",\"is_active\":false}");
        SessionRegistry sessions = mock(SessionRegistry.class);
        AdminService service = service(jdbc, sessions);
        UUID adminId = UUID.randomUUID();

        Map<String, Object> replay = service.updateStatus(
                adminId, "User@Example.com", false, "manual_disable", "same-key");

        assertThat(replay).containsEntry("email", "user@example.com").containsEntry("is_active", false);
        assertThat(jdbc.updates).isEmpty();
        verifyNoInteractions(sessions);

        assertThatThrownBy(() -> service.updateStatus(
                adminId, "User@Example.com", false, "another_reason", "same-key"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("idempotency_key_conflict"));
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void voucherIssueUsesOneRecipientQueryAndOneSetInsert() {
        UUID adminId = UUID.randomUUID();
        VoucherJdbcTemplate jdbc = new VoucherJdbcTemplate(adminId);
        AdminService service = service(jdbc, mock(SessionRegistry.class));

        Map<String, Object> result = service.issueVouchers(adminId, new AdminService.VoucherCommand(
                List.of("b@example.com", "a@example.com"), false, 2,
                "admin_grant", "manual_voucher_grant", "首批内测"), "voucher-key");

        assertThat(result).containsEntry("total_recipients", 2).containsEntry("total_vouchers", 4);
        assertThat(result.get("recipients"))
                .isEqualTo(List.of("a@example.com", "b@example.com"));
        assertThat(jdbc.queries.stream().filter(sql -> sql.contains("WHERE email IN (?, ?)")).count())
                .isEqualTo(1);
        assertThat(jdbc.queries).noneMatch(sql -> sql.contains("FROM users WHERE email = ?"));
        assertThat(jdbc.updates.stream().filter(sql -> sql.contains("INSERT INTO vouchers")).count())
                .isEqualTo(1);
        assertThat(jdbc.updates.stream().filter(sql -> sql.contains("INSERT INTO vouchers")).findFirst())
                .hasValueSatisfying(sql -> assertThat(sql).contains("WITH targets(user_id) AS (VALUES"));
    }

    @Test
    void voucherIssueRejectsMissingRecipientsBeforeDatabaseAccess() {
        EmptyQueryJdbcTemplate jdbc = new EmptyQueryJdbcTemplate();
        AdminService service = service(jdbc, mock(SessionRegistry.class));

        assertThatThrownBy(() -> service.issueVouchers(UUID.randomUUID(), new AdminService.VoucherCommand(
                null, false, 1, "admin_grant", "manual_voucher_grant", null), "voucher-key"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("voucher_recipient_limit"));
        assertThat(jdbc.queries).isEmpty();
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void noteAndRefundReplaysUseExactIdLookupInsteadOfLimitedLists() {
        UUID adminId = UUID.randomUUID();
        ExactReplayJdbcTemplate jdbc = new ExactReplayJdbcTemplate();
        AdminService service = service(jdbc, mock(SessionRegistry.class));

        Map<String, Object> note = service.createNote(adminId, "user@example.com",
                new AdminService.NoteCommand("general", "已通过微信完成沟通", null), "note-key");
        Map<String, Object> refund = service.createRefund(adminId, "user@example.com",
                new AdminService.RefundCreateCommand(
                        "refund_request", "用户申请线下退款", 100, "CNY", 1, null), "refund-key");

        assertThat(note).containsEntry("id", jdbc.noteId);
        assertThat(refund).containsEntry("id", jdbc.refundId);
        assertThat(jdbc.queries).anyMatch(sql -> sql.contains("WHERE n.id = ?"));
        assertThat(jdbc.queries).anyMatch(sql -> sql.contains("WHERE r.id = ?"));
        assertThat(jdbc.queries).noneMatch(sql -> sql.contains("ORDER BY n.created_at DESC LIMIT 100"));
        assertThat(jdbc.queries).noneMatch(sql -> sql.contains("ORDER BY r.created_at DESC LIMIT 200"));
    }

    @Test
    void privacyQueriesNeverSelectRawSafetyContent() {
        EmptyQueryJdbcTemplate jdbc = new EmptyQueryJdbcTemplate();
        AdminService service = service(jdbc, mock(SessionRegistry.class));

        assertThatThrownBy(() -> service.userReport("user@example.com", UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("interview_report_not_found"));
        service.contentSafetyLogs();

        assertThat(jdbc.queries).anySatisfy(sql -> assertThat(sql)
                .contains("s.status NOT IN ('deleting', 'deleted')"));
        assertThat(jdbc.queries).anySatisfy(sql -> assertThat(sql)
                .contains("c.rule_ids::text AS rule_ids_json")
                .contains("c.content_digest", "c.disposition")
                .doesNotContain("matched_terms", "content_excerpt"));
    }

    private static AdminService service(JdbcTemplate jdbc, SessionRegistry sessions) {
        return new AdminService(jdbc, new ObjectMapper(), sessions, Clock.systemUTC());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String operationHash(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                canonical.append("-1:");
            } else {
                String component = value.toString();
                canonical.append(component.length()).append(':').append(component);
            }
            canonical.append(';');
        }
        return sha256(canonical.toString());
    }

    private abstract static class RecordingJdbcTemplate extends JdbcTemplate {
        final List<String> queries = new ArrayList<>();
        final List<String> updates = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return updateResult(sql, args);
        }

        int updateResult(String sql, Object[] args) {
            return 1;
        }

        <T> T map(RowMapper<T> mapper, ResultSet resultSet) {
            try {
                return mapper.mapRow(resultSet, 0);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        }

        ResultSet resultSet() {
            return mock(ResultSet.class);
        }
    }

    private static final class EmptyQueryJdbcTemplate extends RecordingJdbcTemplate {
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper) {
            queries.add(sql);
            return List.of();
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            return List.of();
        }
    }

    private static final class ReplayJdbcTemplate extends RecordingJdbcTemplate {
        private final String requestHash;
        private final String resultJson;

        private ReplayJdbcTemplate(String requestHash, String resultJson) {
            this.requestHash = requestHash;
            this.resultJson = resultJson;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            if (sql.contains("INSERT INTO admin_operations")) {
                return List.of();
            }
            if (sql.contains("SELECT request_hash")) {
                ResultSet rs = resultSet();
                try {
                    when(rs.getString("request_hash")).thenReturn(requestHash);
                    when(rs.getString("result_json")).thenReturn(resultJson);
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
                return List.of(map(mapper, rs));
            }
            throw new AssertionError("Unexpected query: " + sql);
        }
    }

    private static final class VoucherJdbcTemplate extends RecordingJdbcTemplate {
        private final UUID adminId;
        private final UUID operationId = UUID.randomUUID();
        private final UUID firstUserId = UUID.randomUUID();
        private final UUID secondUserId = UUID.randomUUID();

        private VoucherJdbcTemplate(UUID adminId) {
            this.adminId = adminId;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            if (sql.contains("INSERT INTO admin_operations")) {
                ResultSet rs = resultSet();
                try {
                    when(rs.getObject("id", UUID.class)).thenReturn(operationId);
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
                return List.of(map(mapper, rs));
            }
            if (sql.contains("WHERE email IN")) {
                return List.of(
                        map(mapper, user(firstUserId, "b@example.com", "user", true)),
                        map(mapper, user(secondUserId, "a@example.com", "user", true)));
            }
            if (sql.contains("FROM users WHERE id = ?")) {
                return List.of(map(mapper, user(adminId, "admin@example.com", "admin", true)));
            }
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        int updateResult(String sql, Object[] args) {
            return sql.contains("INSERT INTO vouchers") ? 2 : 1;
        }

        private ResultSet user(UUID id, String email, String role, boolean active) {
            ResultSet rs = resultSet();
            try {
                when(rs.getObject("id", UUID.class)).thenReturn(id);
                when(rs.getString("email")).thenReturn(email);
                when(rs.getString("role")).thenReturn(role);
                when(rs.getBoolean("is_active")).thenReturn(active);
                when(rs.getInt("credit_balance")).thenReturn(0);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return rs;
        }
    }

    private static final class ExactReplayJdbcTemplate extends RecordingJdbcTemplate {
        private final UUID userId = UUID.randomUUID();
        private final UUID noteId = UUID.randomUUID();
        private final UUID refundId = UUID.randomUUID();

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            if (sql.contains("FROM users WHERE email = ?")) {
                return List.of(map(mapper, user()));
            }
            if (sql.contains("INSERT INTO customer_notes") || sql.contains("INSERT INTO refund")) {
                return List.of();
            }
            if (sql.contains("FROM customer_notes WHERE admin_id")) {
                return List.of(map(mapper, existingNote()));
            }
            if (sql.contains("FROM refund WHERE created_by_admin_id")) {
                return List.of(map(mapper, existingRefund()));
            }
            if (sql.contains("WHERE n.id = ?")) {
                return List.of(map(mapper, noteView()));
            }
            if (sql.contains("WHERE r.id = ?")) {
                return List.of(map(mapper, refundView()));
            }
            throw new AssertionError("Unexpected query: " + sql);
        }

        private ResultSet user() {
            ResultSet rs = resultSet();
            try {
                when(rs.getObject("id", UUID.class)).thenReturn(userId);
                when(rs.getString("email")).thenReturn("user@example.com");
                when(rs.getString("role")).thenReturn("user");
                when(rs.getBoolean("is_active")).thenReturn(true);
                when(rs.getInt("credit_balance")).thenReturn(0);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return rs;
        }

        private ResultSet existingNote() {
            ResultSet rs = resultSet();
            try {
                when(rs.getObject("id", UUID.class)).thenReturn(noteId);
                when(rs.getObject("user_id", UUID.class)).thenReturn(userId);
                when(rs.getString("category")).thenReturn("general");
                when(rs.getString("content")).thenReturn("已通过微信完成沟通");
                when(rs.getObject("related_session_id", UUID.class)).thenReturn(null);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return rs;
        }

        private ResultSet existingRefund() {
            ResultSet rs = resultSet();
            try {
                when(rs.getObject("id", UUID.class)).thenReturn(refundId);
                when(rs.getObject("user_id", UUID.class)).thenReturn(userId);
                when(rs.getString("reason")).thenReturn("refund_request");
                when(rs.getString("description")).thenReturn("用户申请线下退款");
                when(rs.getObject("amount_cents")).thenReturn(100);
                when(rs.getString("currency")).thenReturn("CNY");
                when(rs.getObject("credit_adjustment")).thenReturn(1);
                when(rs.getObject("related_session_id", UUID.class)).thenReturn(null);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return rs;
        }

        private ResultSet noteView() {
            ResultSet rs = existingNote();
            try {
                when(rs.getString("user_email")).thenReturn("user@example.com");
                when(rs.getString("admin_email")).thenReturn("admin@example.com");
                when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return rs;
        }

        private ResultSet refundView() {
            ResultSet rs = existingRefund();
            try {
                when(rs.getString("user_email")).thenReturn("user@example.com");
                when(rs.getString("status")).thenReturn("open");
                when(rs.getString("resolution")).thenReturn(null);
                when(rs.getString("created_by_admin_email")).thenReturn("admin@example.com");
                when(rs.getString("updated_by_admin_email")).thenReturn(null);
                when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));
                when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.EPOCH));
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return rs;
        }
    }
}
