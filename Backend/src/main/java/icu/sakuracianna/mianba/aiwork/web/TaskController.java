package icu.sakuracianna.mianba.aiwork.web;

import icu.sakuracianna.mianba.aiwork.service.TaskService;
import icu.sakuracianna.mianba.aiwork.service.TaskView;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.platform.web.ValidIdempotencyKey;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户与管理员异步任务的查询、分页和受控重试 HTTP 接口。 */
@Validated
@RestController
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    /**
     * 查询当前用户有权访问的任务详情。
     *
     * @param taskId 任务标识
     * @param principal 当前认证用户
     * @return 任务当前快照
     */
    @GetMapping("/api/tasks/{taskId}")
    public TaskView get(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return tasks.get(principal.userId(), principal.isAdmin(), taskId);
    }

    /**
     * 触发一次受幂等键保护的人工重试。
     *
     * @param taskId 任务标识
     * @param idempotencyKey 本次操作幂等键
     * @param requestId 可选链路标识
     * @param principal 当前认证用户
     * @return 重试后的任务快照
     */
    @PostMapping("/api/tasks/{taskId}/retry")
    public TaskView retry(
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        String safeRequestId = requestId == null || requestId.isBlank() ? "req_unknown" : requestId;
        return tasks.retry(principal.userId(), principal.isAdmin(), taskId, idempotencyKey, safeRequestId);
    }

    /**
     * 分页查询管理端任务列表。
     *
     * @return 符合筛选条件的任务页
     */
    @GetMapping("/api/admin/tasks")
    public TaskService.TaskPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(name = "session_id", required = false) UUID sessionId,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return tasks.list(status, kind, sessionId, limit, offset);
    }
}
