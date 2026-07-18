package icu.sakuracianna.mianba.interview.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import icu.sakuracianna.mianba.aiwork.service.TaskView;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.interview.material.EphemeralMaterial;
import icu.sakuracianna.mianba.interview.material.MaterialService;
import icu.sakuracianna.mianba.interview.service.AnswerAcceptance;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.interview.service.InterviewView;
import icu.sakuracianna.mianba.platform.web.ValidIdempotencyKey;
import icu.sakuracianna.mianba.platform.web.RequestIdFilter;
import icu.sakuracianna.mianba.knowledge.KnowledgeDomain;
import icu.sakuracianna.mianba.knowledge.KnowledgeRagService;
import icu.sakuracianna.mianba.knowledge.PersonalizedQuestionFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 面试会话的创建、查询、回答提交和可恢复内容删除 HTTP 适配层。 */
@RestController
@RequestMapping("/api/interviews")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class InterviewController {
    private final InterviewService service;
    private final MaterialService materials;
    private final KnowledgeRagService knowledge;
    private final PersonalizedQuestionFactory questions;

    @Autowired
    public InterviewController(
            InterviewService service,
            MaterialService materials,
            KnowledgeRagService knowledge,
            PersonalizedQuestionFactory questions) {
        this.service = service;
        this.materials = materials;
        this.knowledge = knowledge;
        this.questions = questions;
    }

    InterviewController(InterviewService service) {
        this(service, null, null, null);
    }

    /**
     * 创建面试会话；相同用户和幂等键必须返回同一业务结果。
     *
     * @return 新建会话快照
     */
    @PostMapping
    public ResponseEntity<InterviewView> start(
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody StartRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        InterviewView session = service.start(
                principal.userId(), request.sessionId(), request.interviewType(), null, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /** 在一个请求内完成材料解析、本地检索、首题生成、会话创建和材料清理。 */
    @PostMapping(path = "/personalized", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<InterviewView> startPersonalized(
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @RequestAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) String requestId,
            @RequestParam("session_id") UUID sessionId,
            @RequestParam("interview_type") @NotBlank String interviewType,
            @RequestParam(value = "resume_file", required = false) MultipartFile resumeFile,
            @RequestParam(value = "job_title", required = false) @Size(max = 160) String jobTitle,
            @RequestParam(value = "job_requirements", required = false) @Size(max = 8000) String jobRequirements,
            @RequestParam(value = "target_school", required = false) @Size(max = 160) String targetSchool,
            @RequestParam(value = "major", required = false) @Size(max = 160) String major,
            @RequestParam(value = "research_direction", required = false) @Size(max = 240) String researchDirection,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        if (materials == null || knowledge == null || questions == null) {
            throw new IllegalStateException("Personalized interview dependencies are unavailable");
        }
        try (EphemeralMaterial material = materials.analyze(
                principal.userId(), requestId, interviewType, resumeFile, jobTitle, jobRequirements,
                targetSchool, major, researchDirection)) {
            KnowledgeDomain domain = "postgraduate".equals(material.interviewType())
                    ? KnowledgeDomain.POSTGRADUATE
                    : KnowledgeDomain.JOB;
            String openingQuestion = questions.openingQuestion(
                    domain, knowledge.retrieve(material.retrievalQuery(), domain));
            InterviewView session = service.startPersonalized(
                    principal.userId(), sessionId, interviewType, idempotencyKey, openingQuestion);
            return ResponseEntity.status(HttpStatus.CREATED).body(session);
        }
    }

    /** 返回当前用户唯一的未结束会话；不存在时响应 204。 */
    @GetMapping("/active")
    public ResponseEntity<InterviewView> active(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.active(principal.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 返回当前用户最近五十条面试历史。 */
    @GetMapping("/history")
    public List<icu.sakuracianna.mianba.interview.service.InterviewHistoryView> history(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return service.history(principal.userId(), 50);
    }

    /** 查询当前用户拥有的指定面试会话。 */
    @GetMapping("/{sessionId}")
    public InterviewView get(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return service.get(principal.userId(), sessionId);
    }

    /** 分阶段擦除指定会话内容，并立即阻断后续查询、答题和 Worker 写回。 */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        service.delete(principal.userId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 接收指定轮次回答并创建异步 AI 任务。
     * 客户端轮次和幂等键共同防止网络重放把同一回答提交到后续问题。
     */
    @PostMapping("/{sessionId}/answers")
    public ResponseEntity<AsyncAnswerResponse> answer(
            @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody AnswerRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) String requestId) {
        AnswerAcceptance accepted = service.answer(
                principal.userId(), sessionId, idempotencyKey,
                request.turnIndex(), request.answerText(), requestId);
        URI location = URI.create("/api/tasks/" + accepted.task().id());
        return ResponseEntity.accepted()
                .location(location)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(new AsyncAnswerResponse(requestId, accepted.session(), accepted.task()));
    }

    /** 单轮回答提交请求。 */
    public record AnswerRequest(
            @JsonProperty("turn_index") @NotNull @PositiveOrZero Integer turnIndex,
            @JsonProperty("answer_text") @NotBlank @Size(max = 8000) String answerText) {
    }

    /** 面试创建请求；会话标识由客户端预生成以支持安全重试。 */
    public record StartRequest(
            @JsonProperty("session_id") @NotNull UUID sessionId,
            @JsonProperty("interview_type") @NotBlank String interviewType) {
    }

    /** 回答受理结果，包含会话快照和可轮询任务。 */
    public record AsyncAnswerResponse(
            @JsonProperty("request_id") String requestId,
            InterviewView session,
            TaskView task) {
    }
}
