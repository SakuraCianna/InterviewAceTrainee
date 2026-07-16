package icu.sakuracianna.mianba.admin.service;

import icu.sakuracianna.mianba.identity.service.IdentitySettings;
import icu.sakuracianna.mianba.identity.service.SessionRegistry;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理后台的查询与写事务边界。
 *
 * 所有资金相关动作只调整平台训练次数或记录人工工单；本服务不调用支付网关，
 * 退款仍由管理员通过微信线下处理。每个管理写操作同时写入不可变审计记录。
 */
@Service
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class AdminService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SessionRegistry sessions;
    private final Clock clock;

    public AdminService(JdbcTemplate jdbc, ObjectMapper mapper, SessionRegistry sessions, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dashboardStats() {
        Map<String, Object> overview = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM users) AS total_users,
                  (SELECT count(*) FROM users WHERE is_active) AS active_users,
                  (SELECT count(*) FROM users WHERE NOT is_active) AS disabled_users,
                  (SELECT count(*) FROM users WHERE role = 'admin') AS admin_users,
                  (SELECT COALESCE(sum(credit_balance), 0) FROM users) AS total_credit_balance,
                  (SELECT COALESCE(sum(change_amount), 0) FROM credit_ledger WHERE change_amount > 0) AS total_credit_granted,
                  (SELECT count(*) FROM sessions) AS total_sessions,
                  (SELECT count(*) FROM sessions WHERE status = 'completed') AS completed_sessions,
                  (SELECT count(*) FROM sessions WHERE status IN ('created','active','awaiting_ai')) AS active_sessions,
                  (SELECT count(*) FROM sessions WHERE created_at >= current_date) AS today_sessions,
                  (SELECT count(*) FROM reports) AS total_reports,
                  (SELECT avg(total_score)::double precision FROM reports) AS average_report_score,
                  (SELECT avg(CASE WHEN success THEN 1.0 ELSE 0.0 END) FROM ai_call_logs) AS ai_success_rate,
                  (SELECT count(*) FROM auth_login WHERE NOT success) AS failed_login_count,
                  (SELECT count(*) FROM refund WHERE status IN ('open','processing','investigating')) AS open_refund_cases
                """, (rs, row) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String name : List.of(
                    "total_users", "active_users", "disabled_users", "admin_users",
                    "total_credit_balance", "total_credit_granted", "total_sessions",
                    "completed_sessions", "active_sessions", "today_sessions", "total_reports",
                    "failed_login_count", "open_refund_cases")) {
                values.put(name, rs.getLong(name));
            }
            values.put("average_report_score", rs.getObject("average_report_score"));
            values.put("ai_success_rate", rs.getObject("ai_success_rate"));
            return values;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database_ready", true);
        result.put("generated_at", clock.instant());
        result.put("overview", overview);
        result.put("user_growth", dailyPoints("users", "created_at"));
        result.put("daily_interviews", dailyPoints("sessions", "created_at"));
        result.put("daily_reports", dailyPoints("reports", "created_at"));
        result.put("interview_type_distribution", distribution("sessions", "interview_type"));
        result.put("session_status_distribution", distribution("sessions", "status"));
        result.put("ai_call_success_distribution", booleanDistribution("ai_call_logs", "success"));
        result.put("login_outcome_distribution", booleanDistribution("auth_login", "success"));
        result.put("refund_status_distribution", distribution("refund", "status"));
        result.put("top_users", topUsers());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> users(String query, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        String normalized = query == null ? "" : query.trim();
        String where = normalized.isEmpty() ? "" : " WHERE u.email::text ILIKE ?";
        Object[] countArgs = normalized.isEmpty() ? new Object[]{} : new Object[]{"%" + normalized + "%"};
        Long totalValue = jdbc.queryForObject("SELECT count(*) FROM users u" + where, Long.class, countArgs);
        List<Object> args = new ArrayList<>(List.of(countArgs));
        args.add(safeLimit);
        args.add(safeOffset);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT u.email::text, u.role, u.is_active, u.credit_balance,
                       count(s.id) AS total_interviews,
                       count(s.id) FILTER (WHERE s.status = 'completed') AS completed_interviews,
                       max(s.created_at) AS last_interview_at
                FROM users u
                LEFT JOIN sessions s ON s.user_id = u.id
                    AND s.status NOT IN ('deleting', 'deleted')
                """ + where + """
                GROUP BY u.id
                ORDER BY u.created_at DESC
                LIMIT ? OFFSET ?
                """, this::mapUser, args.toArray());
        long total = totalValue == null ? 0 : totalValue;
        return Map.of(
                "items", items,
                "total", total,
                "limit", safeLimit,
                "offset", safeOffset,
                "has_more", safeOffset + items.size() < total,
                "total_is_estimated", false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchUsers(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) {
            return List.of();
        }
        return jdbc.query("""
                SELECT u.email::text, u.role, u.is_active, u.credit_balance,
                       count(s.id) AS total_interviews,
                       count(s.id) FILTER (WHERE s.status = 'completed') AS completed_interviews,
                       max(s.created_at) AS last_interview_at
                FROM users u
                LEFT JOIN sessions s ON s.user_id = u.id
                    AND s.status NOT IN ('deleting', 'deleted')
                WHERE u.email::text ILIKE ?
                GROUP BY u.id
                ORDER BY u.email
                LIMIT 20
                """, this::mapUser, "%" + normalized + "%");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> userInterviews(String email) {
        UUID userId = requireUser(email).id();
        return jdbc.query("""
                SELECT s.id, s.interview_type, s.status, s.current_turn_index, s.total_turns,
                       r.total_score, s.created_at
                FROM sessions s LEFT JOIN reports r ON r.session_id = s.id
                WHERE s.user_id = ? AND s.status NOT IN ('deleting', 'deleted')
                ORDER BY s.created_at DESC LIMIT 100
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("session_id", rs.getObject("id", UUID.class));
            item.put("interview_type", rs.getString("interview_type"));
            item.put("status", rs.getString("status"));
            item.put("current_step_index", rs.getInt("current_turn_index"));
            item.put("total_steps", rs.getInt("total_turns"));
            item.put("report_total_score", rs.getObject("total_score"));
            item.put("created_at", rs.getTimestamp("created_at").toInstant());
            return item;
        }, userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> userReport(String email, UUID sessionId) {
        return jdbc.query("""
                SELECT u.email::text, r.report_json::text AS report_json
                FROM reports r
                JOIN sessions s ON s.id = r.session_id
                JOIN users u ON u.id = s.user_id
                WHERE s.id = ? AND u.email = ?
                  AND s.status NOT IN ('deleting', 'deleted')
                """, (rs, row) -> {
            Map<String, Object> report = jsonObject(rs.getString("report_json"));
            report.put("user_email", rs.getString("email"));
            return report;
        }, sessionId, normalizeEmail(email)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "interview_report_not_found", "训练报告不存在"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> creditLedger(String email) {
        return jdbc.query("""
                SELECT l.id, u.email::text AS user_email, l.change_amount, l.balance_after,
                       l.reason, l.related_session_id, operator.email::text AS operator_admin_email,
                       l.note, l.created_at
                FROM credit_ledger l
                JOIN users u ON u.id = l.user_id
                LEFT JOIN users operator ON operator.id = l.operator_admin_id
                WHERE u.email = ? ORDER BY l.created_at DESC LIMIT 100
                """, this::mapCreditLedger, normalizeEmail(email));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> auditLogs() {
        return jdbc.query("""
                SELECT a.id, u.email::text AS admin_email, a.action, a.target_type,
                       a.target_id, a.before_snapshot::text AS before_json,
                       a.after_snapshot::text AS after_json, a.created_at
                FROM admin_audit a JOIN users u ON u.id = a.admin_id
                ORDER BY a.created_at DESC LIMIT 200
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getObject("id", UUID.class));
            item.put("admin_email", rs.getString("admin_email"));
            item.put("action", rs.getString("action"));
            item.put("target_type", rs.getString("target_type"));
            item.put("target_id", rs.getString("target_id"));
            item.put("before_snapshot", jsonNullable(rs.getString("before_json")));
            item.put("after_snapshot", jsonNullable(rs.getString("after_json")));
            item.put("created_at", rs.getTimestamp("created_at").toInstant());
            return item;
        });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> authLogs(String email) {
        String where = email == null || email.isBlank() ? "" : " WHERE email = ?";
        Object[] args = where.isEmpty() ? new Object[]{} : new Object[]{normalizeEmail(email)};
        return jdbc.query("""
                SELECT id, email::text, auth_method, role, success, failure_reason,
                       host(ip_address) AS ip_address, user_agent, created_at
                FROM auth_login
                """ + where + " ORDER BY created_at DESC LIMIT 200", (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getObject("id", UUID.class));
            item.put("email", rs.getString("email"));
            item.put("auth_method", rs.getString("auth_method"));
            item.put("role", rs.getString("role"));
            item.put("success", rs.getBoolean("success"));
            item.put("failure_reason", rs.getString("failure_reason"));
            item.put("ip_address", rs.getString("ip_address"));
            item.put("user_agent", rs.getString("user_agent"));
            item.put("created_at", rs.getTimestamp("created_at").toInstant());
            return item;
        }, args);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> notes(String email) {
        return jdbc.query("""
                SELECT n.id, u.email::text AS user_email, a.email::text AS admin_email,
                       n.category, n.content, n.related_session_id, n.created_at
                FROM customer_notes n
                JOIN users u ON u.id = n.user_id
                JOIN users a ON a.id = n.admin_id
                WHERE u.email = ? ORDER BY n.created_at DESC LIMIT 100
                """, this::mapNote, normalizeEmail(email));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> refunds(String email) {
        String where = email == null || email.isBlank() ? "" : " WHERE u.email = ?";
        Object[] args = where.isEmpty() ? new Object[]{} : new Object[]{normalizeEmail(email)};
        return jdbc.query("""
                SELECT r.id, u.email::text AS user_email, r.status, r.reason, r.description,
                       r.amount_cents, r.currency, r.credit_adjustment, r.related_session_id,
                       r.resolution, creator.email::text AS created_by_admin_email,
                       updater.email::text AS updated_by_admin_email, r.created_at, r.updated_at
                FROM refund r JOIN users u ON u.id = r.user_id
                JOIN users creator ON creator.id = r.created_by_admin_id
                LEFT JOIN users updater ON updater.id = r.updated_by_admin_id
                """ + where + " ORDER BY r.created_at DESC LIMIT 200", this::mapRefund, args);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> aiCallLogs() {
        return jdbc.query("""
                SELECT id, session_id, provider_type, provider_name, model_name, purpose,
                       success, latency_ms, provider_request_id, input_tokens, output_tokens,
                       audio_duration_ms, characters, estimated_cost_cents, error_code, created_at
                FROM ai_call_logs ORDER BY created_at DESC LIMIT 200
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            for (String column : List.of("provider_type", "provider_name", "model_name", "purpose",
                    "provider_request_id", "error_code")) {
                item.put(column, rs.getString(column));
            }
            item.put("id", rs.getObject("id", UUID.class));
            item.put("session_id", rs.getObject("session_id", UUID.class));
            item.put("success", rs.getBoolean("success"));
            for (String column : List.of("latency_ms", "input_tokens", "output_tokens",
                    "audio_duration_ms", "characters", "estimated_cost_cents")) {
                item.put(column, rs.getObject(column));
            }
            item.put("created_at", rs.getTimestamp("created_at").toInstant());
            return item;
        });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> contentSafetyLogs() {
        return jdbc.query("""
                SELECT c.id, u.email::text AS user_email, c.session_id, c.source, c.action,
                       c.risk_level, c.categories::text AS categories_json,
                       CASE WHEN s.status IN ('deleting', 'deleted')
                           THEN '[]'::text ELSE c.matched_terms::text END AS matched_terms_json,
                       CASE WHEN s.status IN ('deleting', 'deleted')
                           THEN NULL ELSE c.content_excerpt END AS content_excerpt,
                       c.message_code, c.created_at
                FROM content_safety c LEFT JOIN users u ON u.id = c.user_id
                LEFT JOIN sessions s ON s.id = c.session_id
                ORDER BY c.created_at DESC LIMIT 200
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getObject("id", UUID.class));
            item.put("user_email", rs.getString("user_email"));
            item.put("session_id", rs.getObject("session_id", UUID.class));
            item.put("source", rs.getString("source"));
            item.put("action", rs.getString("action"));
            item.put("risk_level", rs.getString("risk_level"));
            item.put("categories", jsonStrings(rs.getString("categories_json")));
            item.put("matched_terms", jsonStrings(rs.getString("matched_terms_json")));
            item.put("content_excerpt", rs.getString("content_excerpt"));
            item.put("message_code", rs.getString("message_code"));
            item.put("created_at", rs.getTimestamp("created_at").toInstant());
            return item;
        });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> systemConfigs() {
        return jdbc.query("""
                SELECT config_key, value_json::text AS value_json, description, updated_at
                FROM system_configs ORDER BY config_key
                """, (rs, row) -> configMap(
                rs.getString("config_key"), rs.getString("value_json"),
                rs.getString("description"), rs.getTimestamp("updated_at").toInstant()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> providers() {
        return jdbc.query("""
                SELECT id, provider_type, purpose, provider_name, model_name, priority,
                       region, enabled, api_key_hint
                FROM providers ORDER BY provider_type, purpose, priority
                """, this::mapProvider);
    }

    @Transactional
    public Map<String, Object> adjustCredits(
            UUID adminId,
            String email,
            int change,
            String reason,
            String note,
            String idempotencyKey) {
        // 先锁用户并核对账本幂等记录，再更新余额、写账本和审计；任一数据库步骤失败会整体回滚。
        UserRef user = requireUserForUpdate(email);
        String normalizedReason = safeCode(reason, 80);
        String normalizedNote = nullable(note);
        List<ExistingCreditAdjustment> existing = jdbc.query("""
                SELECT balance_after, change_amount, reason, note, operator_admin_id
                FROM credit_ledger
                WHERE user_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingCreditAdjustment(
                rs.getInt("balance_after"), rs.getInt("change_amount"), rs.getString("reason"),
                rs.getString("note"), rs.getObject("operator_admin_id", UUID.class)),
                user.id(), idempotencyKey);
        if (!existing.isEmpty()) {
            ExistingCreditAdjustment value = existing.getFirst();
            if (!value.matches(adminId, change, normalizedReason, normalizedNote)) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                        "同一幂等键不能用于不同次数调整");
            }
            return Map.of("balance_after", value.balanceAfter());
        }
        long nextBalance = (long) user.creditBalance() + change;
        if (change == 0 || nextBalance < 0 || nextBalance > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "credit_balance_cannot_be_negative", "次数调整结果不合法");
        }
        jdbc.update("""
                UPDATE users SET credit_balance = ?, version = version + 1, updated_at = now()
                WHERE id = ?
                """, (int) nextBalance, user.id());
        jdbc.update("""
                INSERT INTO credit_ledger(
                    user_id, change_amount, balance_after, reason, idempotency_key,
                    operator_admin_id, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, user.id(), change, (int) nextBalance, normalizedReason,
                idempotencyKey, adminId, normalizedNote);
        audit(adminId, "credit_adjust", "user_credit", user.id().toString(),
                Map.of("balance", user.creditBalance()), Map.of("balance", nextBalance, "change", change));
        return Map.of("balance_after", nextBalance);
    }

    @Transactional
    public Map<String, Object> issueVouchers(
            UUID adminId,
            VoucherCommand command,
            String idempotencyKey) {
        if (command.quantity() < 1 || command.quantity() > 20) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_failed", "单次发券数量必须在 1 到 20 之间");
        }
        int quantity = command.quantity();
        String voucherType = safeCode(command.voucherType(), 80);
        String reason = safeCode(command.reason(), 120);
        String note = nullable(command.note());
        // Controller 校验是外层门禁；服务层仍把 null 视为空集合，避免内部调用退化为未审计的 500。
        List<String> sourceEmails = command.userEmails() == null ? List.of() : command.userEmails();
        List<String> normalizedEmails = sourceEmails.stream()
                .map(AdminService::normalizeEmail).distinct().sorted().toList();
        if (!command.issueAllActiveUsers() && (normalizedEmails.isEmpty() || normalizedEmails.size() > 500)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "voucher_recipient_limit", "发券对象必须在 1 到 500 人之间");
        }
        String recipientFingerprint = command.issueAllActiveUsers()
                ? "<all-active-users>"
                : String.join("\u001e", normalizedEmails);
        String requestHash = operationHash(
                recipientFingerprint,
                command.issueAllActiveUsers(),
                quantity,
                voucherType,
                reason,
                note);
        UUID operationId = UUID.randomUUID();
        // 幂等操作占位与后续发券同属一个事务；校验或写入失败时占位也回滚，允许原请求安全重试。
        List<UUID> createdOperation = jdbc.query("""
                INSERT INTO admin_operations(
                    id, admin_id, operation_type, idempotency_key, request_hash)
                VALUES (?, ?, 'voucher_issue', ?, ?)
                ON CONFLICT (admin_id, operation_type, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject("id", UUID.class),
                operationId, adminId, idempotencyKey, requestHash);
        if (createdOperation.isEmpty()) {
            ExistingAdminOperation existing = findAdminOperation(adminId, "voucher_issue", idempotencyKey);
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                        "同一幂等键不能用于不同发券请求");
            }
            if (existing.resultJson() == null) {
                throw new IllegalStateException("Completed voucher operation has no result");
            }
            return jsonObject(existing.resultJson());
        }
        List<UserRef> recipients;
        if (command.issueAllActiveUsers()) {
            recipients = jdbc.query("""
                    SELECT id, email::text, role, is_active, credit_balance FROM users
                    WHERE is_active = true AND role = 'user' ORDER BY created_at LIMIT 501
                    """, this::mapUserRef);
        } else {
            recipients = usersByEmails(normalizedEmails);
        }
        if (recipients.stream().anyMatch(user -> !user.active() || !"user".equals(user.role()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "voucher_recipient_invalid", "体验券只能发给启用中的普通用户");
        }
        if (recipients.isEmpty() || recipients.size() > 500) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "voucher_recipient_limit", "发券对象必须在 1 到 500 人之间");
        }
        // 一次集合 INSERT 代替最多 500 次往返；唯一约束仍逐用户保证同一发券键不会重复落券。
        int inserted = insertVouchers(recipients, idempotencyKey, voucherType, quantity, reason, adminId, note);
        String operatorEmail = requireUserById(adminId).email();
        Map<String, Object> result = Map.of(
                "total_recipients", recipients.size(),
                "total_vouchers", recipients.size() * quantity,
                "recipients", recipients.stream().map(UserRef::email).toList(),
                "voucher_type", voucherType,
                "reason", reason,
                "operator_admin_email", operatorEmail);
        completeAdminOperation(operationId, result);
        audit(adminId, "voucher_issue", "interview_voucher", idempotencyKey,
                null, Map.of("recipients", recipients.size(), "quantity", quantity, "inserted", inserted));
        return result;
    }

    @Transactional
    public Map<String, Object> createNote(
            UUID adminId,
            String email,
            NoteCommand command,
            String idempotencyKey) {
        // 先验证目标用户和关联训练，再尝试唯一键插入；冲突时精确读取原记录并比较全部业务字段。
        UserRef user = requireUser(email);
        String category = safeCode(command.category(), 80);
        String content = requiredText(command.content(), 2, 2000);
        requireOwnedSession(user.id(), command.relatedSessionId());
        UUID candidateId = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO customer_notes(
                    id, user_id, admin_id, idempotency_key, category, content, related_session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (admin_id, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject("id", UUID.class), candidateId, user.id(), adminId,
                idempotencyKey, category, content, command.relatedSessionId());
        UUID noteId;
        if (!inserted.isEmpty()) {
            noteId = inserted.getFirst();
            audit(adminId, "customer_service_note_create", "customer_service_note",
                    noteId.toString(), null, Map.of("user_id", user.id().toString(), "category", category));
        } else {
            ExistingNote existing = findExistingNote(adminId, idempotencyKey);
            if (!existing.matches(user.id(), category, content, command.relatedSessionId())) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                        "同一幂等键不能用于不同客服备注");
            }
            noteId = existing.id();
        }
        return noteById(noteId);
    }

    @Transactional
    public Map<String, Object> createRefund(
            UUID adminId,
            String email,
            RefundCreateCommand command,
            String idempotencyKey) {
        // 工单插入、幂等语义核对和审计共用事务；任何失败都不会留下半张退款工单。
        UserRef user = requireUser(email);
        String reason = safeCode(command.reason(), 120);
        String description = requiredText(command.description(), 2, 3000);
        String currency = safeCode(command.currency(), 16);
        if (command.amountCents() != null && command.amountCents() < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", "退款金额不能为负数");
        }
        requireOwnedSession(user.id(), command.relatedSessionId());
        UUID candidateId = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO refund(
                    id, user_id, reason, description, amount_cents, currency,
                    credit_adjustment, related_session_id, created_by_admin_id,
                    idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (created_by_admin_id, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject("id", UUID.class), candidateId, user.id(), reason,
                description, command.amountCents(), currency, command.creditAdjustment(),
                command.relatedSessionId(), adminId, idempotencyKey);
        UUID refundId;
        if (!inserted.isEmpty()) {
            refundId = inserted.getFirst();
            audit(adminId, "refund_case_create", "refund_case", refundId.toString(), null,
                    Map.of("user_id", user.id().toString(), "status", "open"));
        } else {
            ExistingRefund existing = findExistingRefund(adminId, idempotencyKey);
            if (!existing.matches(
                    user.id(), reason, description, command.amountCents(), currency,
                    command.creditAdjustment(), command.relatedSessionId())) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                        "同一幂等键不能用于不同退款工单");
            }
            refundId = existing.id();
        }
        return refundById(refundId);
    }

    @Transactional
    public Map<String, Object> updateRefund(
            UUID adminId,
            UUID refundId,
            RefundUpdateCommand command,
            String idempotencyKey) {
        String status = command.status().trim().toLowerCase(Locale.ROOT);
        if (!List.of("open", "processing", "investigating", "resolved", "rejected", "cancelled").contains(status)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "refund_status_invalid", "退款工单状态不合法");
        }
        String resolution = optionalText(command.resolution(), 3000);
        AdminOperation operation = beginAdminOperation(adminId, "refund_case_update", idempotencyKey,
                operationHash(refundId, status, resolution));
        if (operation.replayResult() != null) {
            return operation.replayResult();
        }
        // 幂等占位后锁定工单，再更新、审计并保存响应；数据库异常会将四步全部回滚。
        String before = jdbc.query("SELECT status FROM refund WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"), refundId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "refund_case_not_found", "退款工单不存在"));
        jdbc.update("""
                UPDATE refund SET status = ?, resolution = ?, updated_by_admin_id = ?,
                    version = version + 1, updated_at = now() WHERE id = ?
                """, status, resolution, adminId, refundId);
        audit(adminId, "refund_case_update", "refund_case", refundId.toString(),
                Map.of("status", before), Map.of("status", status));
        Map<String, Object> result = refundById(refundId);
        completeAdminOperation(operation.id(), result);
        return result;
    }

    @Transactional
    public Map<String, Object> updateStatus(
            UUID adminId,
            String email,
            boolean active,
            String reason,
            String idempotencyKey) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedReason = optionalText(reason, 120);
        AdminOperation operation = beginAdminOperation(adminId, "user_status_update", idempotencyKey,
                operationHash(normalizedEmail, active, normalizedReason));
        if (operation.replayResult() != null) {
            return operation.replayResult();
        }
        UserRef user = requireUserForUpdate(email);
        if (adminId.equals(user.id()) && !active) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "cannot_disable_own_admin_account", "不能停用当前管理员账号");
        }
        jdbc.update("""
                UPDATE users SET is_active = ?, auth_version = auth_version + 1,
                    version = version + 1, updated_at = now() WHERE id = ?
                """, active, user.id());
        // 数据库状态先变更，再撤销 Redis 会话并写审计；若提交失败，会话至多被额外登出，不会保留越权会话。
        sessions.revokeAll(user.id());
        audit(adminId, "user_status_update", "user", user.id().toString(),
                Map.of("is_active", user.active()), snapshotWithReason("is_active", active, normalizedReason));
        Map<String, Object> result = Map.of("email", user.email(), "is_active", active);
        completeAdminOperation(operation.id(), result);
        return result;
    }

    @Transactional
    public Map<String, Object> updateRole(
            UUID adminId,
            String email,
            String role,
            String reason,
            String idempotencyKey) {
        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (!List.of("user", "admin").contains(normalizedRole)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "role_invalid", "账号角色不合法");
        }
        String normalizedEmail = normalizeEmail(email);
        String normalizedReason = optionalText(reason, 120);
        AdminOperation operation = beginAdminOperation(adminId, "user_role_update", idempotencyKey,
                operationHash(normalizedEmail, normalizedRole, normalizedReason));
        if (operation.replayResult() != null) {
            return operation.replayResult();
        }
        UserRef user = requireUserForUpdate(email);
        if (adminId.equals(user.id()) && !"admin".equals(normalizedRole)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "cannot_change_own_admin_role", "不能撤销当前管理员自己的权限");
        }
        jdbc.update("""
                UPDATE users SET role = ?, auth_version = auth_version + 1,
                    version = version + 1, updated_at = now() WHERE id = ?
                """, normalizedRole, user.id());
        // 角色写入后立即撤销旧会话；即使数据库最终提交失败，也只会造成安全侧的重新登录。
        sessions.revokeAll(user.id());
        audit(adminId, "user_role_update", "user", user.id().toString(),
                Map.of("role", user.role()), snapshotWithReason("role", normalizedRole, normalizedReason));
        Map<String, Object> result = Map.of("email", user.email(), "role", normalizedRole);
        completeAdminOperation(operation.id(), result);
        return result;
    }

    @Transactional
    public Map<String, Object> updateSystemConfig(
            UUID adminId,
            String key,
            Object value,
            String idempotencyKey) {
        Object normalizedValue;
        try {
            normalizedValue = IdentitySettings.normalizeConfigValue(key, value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "system_config_value_invalid", "系统配置值的类型或范围不合法");
        }
        String json = writeJson(normalizedValue);
        AdminOperation operation = beginAdminOperation(adminId, "system_config_update", idempotencyKey,
                operationHash(key, json));
        if (operation.replayResult() != null) {
            return operation.replayResult();
        }
        String before = jdbc.query("""
                SELECT value_json::text FROM system_configs WHERE config_key = ? FOR UPDATE
                """, (rs, row) -> rs.getString(1), key).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "system_config_not_found", "系统配置不存在"));
        jdbc.update("""
                UPDATE system_configs SET value_json = ?::jsonb, updated_by_admin_id = ?,
                    version = version + 1, updated_at = now() WHERE config_key = ?
                """, json, adminId, key);
        audit(adminId, "system_config_update", "system_config", key,
                jsonNullable(before), jsonNullable(json));
        Map<String, Object> result = systemConfigByKey(key);
        completeAdminOperation(operation.id(), result);
        return result;
    }

    @Transactional
    public Map<String, Object> updateProvider(
            UUID adminId,
            UUID providerId,
            boolean enabled,
            String idempotencyKey) {
        AdminOperation operation = beginAdminOperation(adminId, "provider_config_update", idempotencyKey,
                operationHash(providerId, enabled));
        if (operation.replayResult() != null) {
            return operation.replayResult();
        }
        Boolean before = jdbc.query("SELECT enabled FROM providers WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getBoolean("enabled"), providerId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "provider_config_not_found", "供应商配置不存在"));
        jdbc.update("""
                UPDATE providers SET enabled = ?, version = version + 1, updated_at = now()
                WHERE id = ?
                """, enabled, providerId);
        audit(adminId, "provider_config_update", "provider_config", providerId.toString(),
                Map.of("enabled", before), Map.of("enabled", enabled));
        Map<String, Object> result = providerById(providerId);
        completeAdminOperation(operation.id(), result);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateProvider(UUID providerId) {
        Map<String, Object> provider = providers().stream()
                .filter(item -> providerId.equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "provider_config_not_found", "供应商配置不存在"));
        if (!Boolean.TRUE.equals(provider.get("enabled")) || !Boolean.TRUE.equals(provider.get("has_api_key"))) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "provider_not_available", "供应商未启用或凭据未配置");
        }
        return Map.of(
                "success", true,
                "detail", "provider_configuration_validated",
                "message", "配置完整；真实模型调用由隔离 Worker 执行");
    }

    private List<Map<String, Object>> dailyPoints(String table, String timestampColumn) {
        return jdbc.query("SELECT to_char(" + timestampColumn + " AT TIME ZONE 'Asia/Shanghai', 'MM-DD') AS label,"
                        + " count(*) AS value FROM " + table
                        + " WHERE " + timestampColumn + " >= now() - interval '6 days'"
                        + " GROUP BY 1 ORDER BY min(" + timestampColumn + ")",
                (rs, row) -> Map.of("label", rs.getString("label"), "value", rs.getLong("value")));
    }

    private List<Map<String, Object>> distribution(String table, String column) {
        return jdbc.query("SELECT " + column + "::text AS label, count(*) AS value FROM " + table
                        + " GROUP BY " + column + " ORDER BY value DESC",
                (rs, row) -> Map.of("label", rs.getString("label"), "value", rs.getLong("value")));
    }

    private List<Map<String, Object>> booleanDistribution(String table, String column) {
        return jdbc.query("SELECT CASE WHEN " + column + " THEN 'success' ELSE 'failed' END AS label,"
                        + " count(*) AS value FROM " + table + " GROUP BY " + column + " ORDER BY label",
                (rs, row) -> Map.of("label", rs.getString("label"), "value", rs.getLong("value")));
    }

    private List<Map<String, Object>> topUsers() {
        return jdbc.query("""
                SELECT u.email::text, count(s.id) AS total_interviews,
                       count(s.id) FILTER (WHERE s.status = 'completed') AS completed_interviews,
                       u.credit_balance, max(s.created_at) AS last_interview_at
                FROM users u LEFT JOIN sessions s ON s.user_id = u.id
                    AND s.status NOT IN ('deleting', 'deleted')
                GROUP BY u.id ORDER BY total_interviews DESC, u.created_at LIMIT 10
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("email", rs.getString("email"));
            item.put("total_interviews", rs.getLong("total_interviews"));
            item.put("completed_interviews", rs.getLong("completed_interviews"));
            item.put("credit_balance", rs.getInt("credit_balance"));
            item.put("last_interview_at", timestampOrNull(rs, "last_interview_at"));
            return item;
        });
    }

    private Map<String, Object> mapUser(ResultSet rs, int row) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("email", rs.getString("email"));
        item.put("role", rs.getString("role"));
        item.put("is_active", rs.getBoolean("is_active"));
        item.put("credit_balance", rs.getInt("credit_balance"));
        item.put("total_interviews", rs.getLong("total_interviews"));
        item.put("completed_interviews", rs.getLong("completed_interviews"));
        item.put("last_interview_at", timestampOrNull(rs, "last_interview_at"));
        return item;
    }

    private Map<String, Object> mapCreditLedger(ResultSet rs, int row) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getObject("id", UUID.class));
        item.put("user_email", rs.getString("user_email"));
        item.put("change_amount", rs.getInt("change_amount"));
        item.put("balance_after", rs.getInt("balance_after"));
        item.put("reason", rs.getString("reason"));
        item.put("related_session_id", rs.getObject("related_session_id", UUID.class));
        item.put("operator_admin_email", rs.getString("operator_admin_email"));
        item.put("note", rs.getString("note"));
        item.put("created_at", rs.getTimestamp("created_at").toInstant());
        return item;
    }

    private Map<String, Object> mapNote(ResultSet rs, int row) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getObject("id", UUID.class));
        item.put("user_email", rs.getString("user_email"));
        item.put("admin_email", rs.getString("admin_email"));
        item.put("category", rs.getString("category"));
        item.put("content", rs.getString("content"));
        item.put("related_session_id", rs.getObject("related_session_id", UUID.class));
        item.put("created_at", rs.getTimestamp("created_at").toInstant());
        return item;
    }

    private Map<String, Object> mapRefund(ResultSet rs, int row) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getObject("id", UUID.class));
        for (String column : List.of("user_email", "status", "reason", "description", "currency",
                "resolution", "created_by_admin_email", "updated_by_admin_email")) {
            item.put(column, rs.getString(column));
        }
        item.put("amount_cents", rs.getObject("amount_cents"));
        item.put("credit_adjustment", rs.getObject("credit_adjustment"));
        item.put("related_session_id", rs.getObject("related_session_id", UUID.class));
        item.put("created_at", rs.getTimestamp("created_at").toInstant());
        item.put("updated_at", rs.getTimestamp("updated_at").toInstant());
        return item;
    }

    private Map<String, Object> mapProvider(ResultSet rs, int row) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getObject("id", UUID.class));
        for (String column : List.of("provider_type", "purpose", "provider_name", "model_name", "region")) {
            item.put(column, rs.getString(column));
        }
        item.put("priority", rs.getInt("priority"));
        item.put("enabled", rs.getBoolean("enabled"));
        String hint = rs.getString("api_key_hint");
        item.put("has_api_key", hint != null && !hint.isBlank());
        item.put("api_key_preview", hint == null ? null : "server-secret");
        return item;
    }

    private UserRef requireUser(String email) {
        return jdbc.query("""
                SELECT id, email::text, role, is_active, credit_balance FROM users WHERE email = ?
                """, this::mapUserRef, normalizeEmail(email)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));
    }

    private UserRef requireUserForUpdate(String email) {
        return jdbc.query("""
                SELECT id, email::text, role, is_active, credit_balance FROM users
                WHERE email = ? FOR UPDATE
                """, this::mapUserRef, normalizeEmail(email)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));
    }

    private UserRef requireUserById(UUID id) {
        return jdbc.query("""
                SELECT id, email::text, role, is_active, credit_balance FROM users WHERE id = ?
                """, this::mapUserRef, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在"));
    }

    private List<UserRef> usersByEmails(List<String> emails) {
        if (emails.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", emails.stream().map(ignored -> "?").toList());
        List<UserRef> found = jdbc.query("""
                SELECT id, email::text, role, is_active, credit_balance FROM users
                WHERE email IN (%s)
                """.formatted(placeholders), this::mapUserRef, emails.toArray());
        Map<String, UserRef> byEmail = new LinkedHashMap<>();
        found.forEach(user -> byEmail.put(normalizeEmail(user.email()), user));
        return emails.stream().map(email -> {
            UserRef user = byEmail.get(email);
            if (user == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在");
            }
            return user;
        }).toList();
    }

    private int insertVouchers(
            List<UserRef> recipients,
            String idempotencyKey,
            String voucherType,
            int quantity,
            String reason,
            UUID adminId,
            String note) {
        String targetRows = String.join(", ", recipients.stream()
                .map(ignored -> "(CAST(? AS uuid))").toList());
        List<Object> arguments = new ArrayList<>(recipients.size() + 6);
        recipients.forEach(user -> arguments.add(user.id()));
        arguments.add(idempotencyKey);
        arguments.add(voucherType);
        arguments.add(quantity);
        arguments.add(reason);
        arguments.add(adminId);
        arguments.add(note);
        return jdbc.update("""
                WITH targets(user_id) AS (VALUES %s)
                INSERT INTO vouchers(
                    user_id, issue_idempotency_key, voucher_type, remaining_uses,
                    status, issue_reason, issued_by_admin_id, note, expires_at)
                SELECT target.user_id, ?, ?, ?, 'available', ?, ?, ?, now() + interval '90 days'
                FROM targets target
                ON CONFLICT (user_id, issue_idempotency_key) DO NOTHING
                """.formatted(targetRows), arguments.toArray());
    }

    private void requireOwnedSession(UUID userId, UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        Boolean owned = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM sessions WHERE id = ? AND user_id = ?)",
                Boolean.class, sessionId, userId);
        if (!Boolean.TRUE.equals(owned)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "related_session_not_owned", "关联面试不属于目标用户");
        }
    }

    private ExistingNote findExistingNote(UUID adminId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, user_id, category, content, related_session_id
                FROM customer_notes WHERE admin_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingNote(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("category"),
                rs.getString("content"),
                rs.getObject("related_session_id", UUID.class)), adminId, idempotencyKey)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Conflicting customer note was not found"));
    }

    private ExistingRefund findExistingRefund(UUID adminId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, user_id, reason, description, amount_cents, currency,
                       credit_adjustment, related_session_id
                FROM refund WHERE created_by_admin_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingRefund(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("reason"),
                rs.getString("description"),
                (Integer) rs.getObject("amount_cents"),
                rs.getString("currency"),
                (Integer) rs.getObject("credit_adjustment"),
                rs.getObject("related_session_id", UUID.class)), adminId, idempotencyKey)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Conflicting refund case was not found"));
    }

    private Map<String, Object> noteById(UUID noteId) {
        return jdbc.query("""
                SELECT n.id, u.email::text AS user_email, a.email::text AS admin_email,
                       n.category, n.content, n.related_session_id, n.created_at
                FROM customer_notes n
                JOIN users u ON u.id = n.user_id
                JOIN users a ON a.id = n.admin_id
                WHERE n.id = ?
                """, this::mapNote, noteId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Created customer note was not found"));
    }

    private Map<String, Object> refundById(UUID refundId) {
        return jdbc.query("""
                SELECT r.id, u.email::text AS user_email, r.status, r.reason, r.description,
                       r.amount_cents, r.currency, r.credit_adjustment, r.related_session_id,
                       r.resolution, creator.email::text AS created_by_admin_email,
                       updater.email::text AS updated_by_admin_email, r.created_at, r.updated_at
                FROM refund r JOIN users u ON u.id = r.user_id
                JOIN users creator ON creator.id = r.created_by_admin_id
                LEFT JOIN users updater ON updater.id = r.updated_by_admin_id
                WHERE r.id = ?
                """, this::mapRefund, refundId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Refund case was not found after mutation"));
    }

    private Map<String, Object> systemConfigByKey(String key) {
        return jdbc.query("""
                SELECT config_key, value_json::text AS value_json, description, updated_at
                FROM system_configs WHERE config_key = ?
                """, (rs, row) -> configMap(
                rs.getString("config_key"), rs.getString("value_json"),
                rs.getString("description"), rs.getTimestamp("updated_at").toInstant()), key)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("System config was not found after mutation"));
    }

    private Map<String, Object> providerById(UUID providerId) {
        return jdbc.query("""
                SELECT id, provider_type, purpose, provider_name, model_name, priority,
                       region, enabled, api_key_hint
                FROM providers WHERE id = ?
                """, this::mapProvider, providerId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Provider config was not found after mutation"));
    }

    private AdminOperation beginAdminOperation(
            UUID adminId,
            String operationType,
            String idempotencyKey,
            String requestHash) {
        UUID operationId = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO admin_operations(
                    id, admin_id, operation_type, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (admin_id, operation_type, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject("id", UUID.class), operationId, adminId,
                operationType, idempotencyKey, requestHash);
        if (!inserted.isEmpty()) {
            return new AdminOperation(inserted.getFirst(), null);
        }
        ExistingAdminOperation existing = findAdminOperation(adminId, operationType, idempotencyKey);
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_conflict",
                    "同一幂等键不能用于不同管理操作");
        }
        if (existing.resultJson() == null) {
            throw new IllegalStateException("Completed admin operation has no result");
        }
        return new AdminOperation(null, jsonObject(existing.resultJson()));
    }

    private void completeAdminOperation(UUID operationId, Map<String, Object> result) {
        int updated = jdbc.update("""
                UPDATE admin_operations SET result_json = ?::jsonb, completed_at = now()
                WHERE id = ? AND result_json IS NULL
                """, writeJson(result), operationId);
        if (updated != 1) {
            throw new IllegalStateException("Admin operation result was not persisted");
        }
    }

    private ExistingAdminOperation findAdminOperation(
            UUID adminId,
            String operationType,
            String idempotencyKey) {
        return jdbc.query("""
                SELECT request_hash, result_json::text AS result_json
                FROM admin_operations
                WHERE admin_id = ? AND operation_type = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingAdminOperation(
                rs.getString("request_hash"), rs.getString("result_json")),
                adminId, operationType, idempotencyKey).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Conflicting admin operation was not found"));
    }

    private UserRef mapUserRef(ResultSet rs, int row) throws SQLException {
        return new UserRef(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("role"), rs.getBoolean("is_active"), rs.getInt("credit_balance"));
    }

    private void audit(
            UUID adminId,
            String action,
            String targetType,
            String targetId,
            Object before,
            Object after) {
        jdbc.update("""
                INSERT INTO admin_audit(
                    admin_id, action, target_type, target_id, before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, adminId, action, targetType, targetId,
                before == null ? null : writeJson(before), after == null ? null : writeJson(after));
    }

    private Map<String, Object> configMap(String key, String json, String description, Object updatedAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("value", jsonNullable(json));
        item.put("description", description);
        item.put("updated_at", updatedAt);
        return item;
    }

    private static Map<String, Object> snapshotWithReason(String field, Object value, String reason) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(field, value);
        snapshot.put("reason", reason);
        return snapshot;
    }

    private Map<String, Object> jsonObject(String json) {
        Object value = jsonNullable(json);
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, item);
            }
        });
        return result;
    }

    private Object jsonNullable(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid JSON stored in database", exception);
        }
    }

    private List<String> jsonStrings(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.of(mapper.readValue(json, String[].class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid string array stored in database", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize admin audit JSON", exception);
        }
    }

    private static Object timestampOrNull(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return validatedFreeText(value.trim());
    }

    private static String optionalText(String value, int maxLength) {
        String normalized = nullable(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", "文本长度不正确");
        }
        return normalized;
    }

    private static String safeCode(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || !normalized.matches("[A-Za-z0-9_./-]+")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", "业务代码格式不正确");
        }
        return normalized;
    }

    private static String requiredText(String value, int minLength, int maxLength) {
        String normalized = value == null ? "" : validatedFreeText(value.trim());
        if (normalized.length() < minLength || normalized.length() > maxLength) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", "文本长度不正确");
        }
        return normalized;
    }

    private static String validatedFreeText(String value) {
        boolean unsupportedControl = value.codePoints().anyMatch(character ->
                Character.isISOControl(character)
                        && character != '\n'
                        && character != '\r'
                        && character != '\t');
        if (unsupportedControl) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_failed", "文本包含不支持的控制字符");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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

    /** 发券事务的已校验业务参数。 */
    public record VoucherCommand(
            List<String> userEmails,
            boolean issueAllActiveUsers,
            int quantity,
            String voucherType,
            String reason,
            String note) {
        public VoucherCommand {
            userEmails = List.copyOf(userEmails == null ? List.of() : userEmails);
        }
    }

    /** 客服备注事务的已校验业务参数。 */
    public record NoteCommand(String category, String content, UUID relatedSessionId) {
    }

    /** 退款工单创建事务的已校验业务参数。 */
    public record RefundCreateCommand(
            String reason,
            String description,
            Integer amountCents,
            String currency,
            Integer creditAdjustment,
            UUID relatedSessionId) {
    }

    /** 退款工单更新事务的已校验业务参数。 */
    public record RefundUpdateCommand(String status, String resolution) {
    }

    private record UserRef(UUID id, String email, String role, boolean active, int creditBalance) {
    }

    private record ExistingCreditAdjustment(
            int balanceAfter,
            int change,
            String reason,
            String note,
            UUID adminId) {
        private boolean matches(UUID requestedAdminId, int requestedChange, String requestedReason, String requestedNote) {
            return Objects.equals(adminId, requestedAdminId)
                    && change == requestedChange
                    && reason.equals(requestedReason)
                    && Objects.equals(note, requestedNote);
        }
    }

    private record ExistingAdminOperation(String requestHash, String resultJson) {
    }

    private record AdminOperation(UUID id, Map<String, Object> replayResult) {
    }

    private record ExistingNote(
            UUID id,
            UUID userId,
            String category,
            String content,
            UUID relatedSessionId) {
        private boolean matches(UUID requestedUserId, String requestedCategory, String requestedContent,
                UUID requestedSessionId) {
            return userId.equals(requestedUserId)
                    && category.equals(requestedCategory)
                    && content.equals(requestedContent)
                    && Objects.equals(relatedSessionId, requestedSessionId);
        }
    }

    private record ExistingRefund(
            UUID id,
            UUID userId,
            String reason,
            String description,
            Integer amountCents,
            String currency,
            Integer creditAdjustment,
            UUID relatedSessionId) {
        private boolean matches(
                UUID requestedUserId,
                String requestedReason,
                String requestedDescription,
                Integer requestedAmountCents,
                String requestedCurrency,
                Integer requestedCreditAdjustment,
                UUID requestedSessionId) {
            return userId.equals(requestedUserId)
                    && reason.equals(requestedReason)
                    && description.equals(requestedDescription)
                    && Objects.equals(amountCents, requestedAmountCents)
                    && currency.equals(requestedCurrency)
                    && Objects.equals(creditAdjustment, requestedCreditAdjustment)
                    && Objects.equals(relatedSessionId, requestedSessionId);
        }
    }
}
