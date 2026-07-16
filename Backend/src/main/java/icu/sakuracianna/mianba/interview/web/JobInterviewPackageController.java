package icu.sakuracianna.mianba.interview.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPackageService;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPackageView;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage;
import icu.sakuracianna.mianba.platform.web.ValidIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作面试套餐的所有者范围 HTTP 适配层。 */
@Validated
@RestController
@RequestMapping("/api/interview-packages")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class JobInterviewPackageController {
    private final JobInterviewPackageService service;

    public JobInterviewPackageController(JobInterviewPackageService service) {
        this.service = service;
    }

    /** 创建套餐并启动技术一面；所有者和计费属性不接受客户端输入。 */
    @PostMapping
    public ResponseEntity<PackageResponse> create(
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        JobInterviewPackageView created = service.create(
                principal.userId(),
                request.packageId(),
                request.firstSessionId(),
                request.materialId(),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(packageLocation(created.packageId()))
                .body(PackageResponse.from(created));
    }

    /** 返回当前所有者的活跃套餐，不存在时响应 204。 */
    @GetMapping("/active")
    public ResponseEntity<PackageResponse> active(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return service.active(principal.userId())
                .map(PackageResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 返回当前所有者指定的套餐投影。 */
    @GetMapping("/{packageId}")
    public PackageResponse get(
            @PathVariable UUID packageId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return PackageResponse.from(service.get(principal.userId(), packageId));
    }

    /** 显式启动当前可用的后续面试阶段。 */
    @PostMapping("/{packageId}/stages/{stageCode}/start")
    public ResponseEntity<PackageResponse> startStage(
            @PathVariable UUID packageId,
            @PathVariable JobInterviewStage stageCode,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody StartStageRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        JobInterviewPackageView started = service.startStage(
                principal.userId(), packageId, stageCode, request.sessionId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(packageLocation(started.packageId()))
                .body(PackageResponse.from(started));
    }

    private static URI packageLocation(UUID packageId) {
        return URI.create("/api/interview-packages/" + packageId);
    }

    /** 套餐创建请求；UUID 由客户端预生成以支持安全重试。 */
    public record CreateRequest(
            @JsonProperty("package_id") @NotNull UUID packageId,
            @JsonProperty("first_session_id") @NotNull UUID firstSessionId,
            @JsonProperty("material_id") @NotNull UUID materialId) {
    }

    /** 后续阶段启动请求；阶段顺序和计划由服务端决定。 */
    public record StartStageRequest(
            @JsonProperty("session_id") @NotNull UUID sessionId) {
    }

    /** 不含材料、计划或上下文快照的套餐响应。 */
    public record PackageResponse(
            @JsonProperty("package_id") UUID packageId,
            String status,
            @JsonProperty("current_stage_code") JobInterviewStage currentStageCode,
            @JsonProperty("charged_credit") int chargedCredit,
            @JsonProperty("admin_unlimited_usage") boolean adminUnlimitedUsage,
            @JsonProperty("expires_at") Instant expiresAt,
            List<StageResponse> stages) {
        private static PackageResponse from(JobInterviewPackageView view) {
            return new PackageResponse(
                    view.packageId(),
                    view.status(),
                    view.currentStageCode(),
                    view.chargedCredit(),
                    view.adminUnlimitedUsage(),
                    view.expiresAt(),
                    view.stages().stream().map(StageResponse::from).toList());
        }
    }

    /** 单个套餐阶段的安全响应投影。 */
    public record StageResponse(
            @JsonProperty("stage_code") JobInterviewStage stageCode,
            int sequence,
            String status,
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("min_turns") int minTurns,
            @JsonProperty("max_turns") int maxTurns,
            @JsonProperty("target_duration_minutes") int targetDurationMinutes,
            @JsonProperty("required_sections") List<String> requiredSections) {
        private static StageResponse from(JobInterviewPackageView.Stage stage) {
            return new StageResponse(
                    stage.stageCode(),
                    stage.sequence(),
                    stage.status(),
                    stage.sessionId(),
                    stage.minTurns(),
                    stage.maxTurns(),
                    stage.targetDurationMinutes(),
                    stage.requiredSections());
        }
    }
}
