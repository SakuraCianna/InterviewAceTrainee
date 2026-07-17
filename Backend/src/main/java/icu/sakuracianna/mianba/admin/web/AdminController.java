package icu.sakuracianna.mianba.admin.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import icu.sakuracianna.mianba.admin.service.AdminService;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.platform.web.ValidIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 管理后台 HTTP 适配层；业务事务与审计统一委托给 {@link AdminService}。 */
@Validated
@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class AdminController {
    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return admin.dashboardStats();
    }

    @GetMapping("/users")
    public Map<String, Object> users(
            @RequestParam(defaultValue = "") @Size(max = 160) String query,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return admin.users(query, limit, offset);
    }

    @GetMapping("/users/search")
    public List<Map<String, Object>> searchUsers(
            @RequestParam @NotBlank @Size(max = 160) String query) {
        return admin.searchUsers(query);
    }

    @GetMapping("/users/{email}/interviews")
    public List<Map<String, Object>> userInterviews(@PathVariable @Email String email) {
        return admin.userInterviews(email);
    }

    @GetMapping("/users/{email}/interviews/{sessionId}/report")
    public Map<String, Object> userReport(
            @PathVariable @Email String email,
            @PathVariable UUID sessionId) {
        return admin.userReport(email, sessionId);
    }

    @GetMapping("/users/{email}/credit-ledger")
    public List<Map<String, Object>> creditLedger(@PathVariable @Email String email) {
        return admin.creditLedger(email);
    }

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> auditLogs() {
        return admin.auditLogs();
    }

    @GetMapping("/auth-login-logs")
    public List<Map<String, Object>> authLogs() {
        return admin.authLogs(null);
    }

    @GetMapping("/users/{email}/auth-login-logs")
    public List<Map<String, Object>> userAuthLogs(@PathVariable @Email String email) {
        return admin.authLogs(email);
    }

    @GetMapping("/users/{email}/notes")
    public List<Map<String, Object>> notes(@PathVariable @Email String email) {
        return admin.notes(email);
    }

    @GetMapping("/refund-cases")
    public List<Map<String, Object>> refunds() {
        return admin.refunds(null);
    }

    @GetMapping("/users/{email}/refund-cases")
    public List<Map<String, Object>> userRefunds(@PathVariable @Email String email) {
        return admin.refunds(email);
    }

    @GetMapping("/ai-call-logs")
    public List<Map<String, Object>> aiCallLogs() {
        return admin.aiCallLogs();
    }

    @GetMapping("/content-safety-logs")
    public List<Map<String, Object>> safetyLogs() {
        return admin.contentSafetyLogs();
    }

    @GetMapping("/system-configs")
    public List<Map<String, Object>> systemConfigs() {
        return admin.systemConfigs();
    }

    @PostMapping("/users/{email}/credits")
    public Map<String, Object> adjustCredits(
            @PathVariable @Email String email,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody CreditCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.adjustCredits(principal.userId(), email, command.changeAmount(),
                command.reason(), command.note(), idempotencyKey);
    }

    @PostMapping("/vouchers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issueVouchers(
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody VoucherCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.issueVouchers(principal.userId(), new AdminService.VoucherCommand(
                command.userEmails(), command.issueAllActiveUsers(), command.quantity(),
                command.voucherType(), command.reason(), command.note()), idempotencyKey);
    }

    @PostMapping("/users/{email}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createNote(
            @PathVariable @Email String email,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody NoteCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.createNote(principal.userId(), email,
                new AdminService.NoteCommand(command.category(), command.content(), command.relatedSessionId()),
                idempotencyKey);
    }

    @PostMapping("/users/{email}/refund-cases")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRefund(
            @PathVariable @Email String email,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody RefundCreateCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.createRefund(principal.userId(), email, new AdminService.RefundCreateCommand(
                command.reason(), command.description(), command.amountCents(), command.currency(),
                command.creditAdjustment(), command.relatedSessionId()), idempotencyKey);
    }

    @PutMapping("/refund-cases/{caseId}")
    public Map<String, Object> updateRefund(
            @PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody RefundUpdateCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.updateRefund(principal.userId(), caseId,
                new AdminService.RefundUpdateCommand(command.status(), command.resolution()), idempotencyKey);
    }

    @PutMapping("/users/{email}/status")
    public Map<String, Object> updateStatus(
            @PathVariable @Email String email,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody StatusCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.updateStatus(principal.userId(), email, command.active(), command.reason(), idempotencyKey);
    }

    @PutMapping("/users/{email}/role")
    public Map<String, Object> updateRole(
            @PathVariable @Email String email,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody RoleCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.updateRole(principal.userId(), email, command.role(), command.reason(), idempotencyKey);
    }

    @PutMapping("/system-configs/{configKey}")
    public Map<String, Object> updateSystemConfig(
            @PathVariable @NotBlank @Size(max = 120) String configKey,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody ConfigCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.updateSystemConfig(principal.userId(), configKey, command.value(), idempotencyKey);
    }

    /** 管理员人工调整用户训练次数的请求。 */
    public record CreditCommand(
            @JsonProperty("change_amount") @Min(-1000) @Max(1000) int changeAmount,
            @NotBlank @Size(max = 80) String reason,
            @Size(max = 1000) String note) {
    }

    /** 管理员向指定用户或全部启用用户发放体验券的请求。 */
    public record VoucherCommand(
            @JsonProperty("user_emails") @NotNull @Size(max = 500) List<@Email String> userEmails,
            @JsonProperty("issue_all_active_users") boolean issueAllActiveUsers,
            @Min(1) @Max(20) int quantity,
            @JsonProperty("voucher_type") @NotBlank @Size(max = 80) String voucherType,
            @NotBlank @Size(max = 120) String reason,
            @Size(max = 1000) String note) {
    }

    /** 客服沟通备注创建请求。 */
    public record NoteCommand(
            @NotBlank @Size(max = 80) String category,
            @NotBlank @Size(min = 2, max = 2000) String content,
            @JsonProperty("related_session_id") UUID relatedSessionId) {
    }

    /** 线下退款或纠纷工单创建请求。 */
    public record RefundCreateCommand(
            @NotBlank @Size(max = 120) String reason,
            @NotBlank @Size(min = 2, max = 3000) String description,
            @JsonProperty("amount_cents") @Min(0) Integer amountCents,
            @NotBlank @Size(max = 16) String currency,
            @JsonProperty("credit_adjustment") Integer creditAdjustment,
            @JsonProperty("related_session_id") UUID relatedSessionId) {
    }

    /** 退款或纠纷工单状态更新请求。 */
    public record RefundUpdateCommand(
            @NotBlank @Size(max = 32) String status,
            @Size(max = 3000) String resolution) {
    }

    /** 用户启用状态更新请求，原因用于审计和幂等语义校验。 */
    public record StatusCommand(@JsonProperty("is_active") boolean active, @Size(max = 120) String reason) {
    }

    /** 用户角色更新请求，原因用于审计和幂等语义校验。 */
    public record RoleCommand(@NotBlank String role, @Size(max = 120) String reason) {
    }

    /** 系统配置值更新请求。 */
    public record ConfigCommand(@NotNull Object value) {
    }
}
