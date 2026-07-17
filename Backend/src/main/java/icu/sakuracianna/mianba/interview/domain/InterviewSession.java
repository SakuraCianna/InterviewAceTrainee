package icu.sakuracianna.mianba.interview.domain;

import java.util.Objects;
import java.util.UUID;

/** 在内存中校验面试状态迁移的领域实体。 */
public final class InterviewSession {
    private final UUID id;
    private final UUID userId;
    private final InterviewType type;
    private final int totalTurns;
    private SessionStatus status;
    private int currentTurnIndex;
    private long version;
    private String pendingIdempotencyKey;
    private String pendingAnswer;
    private String currentQuestion;

    private InterviewSession(UUID id, UUID userId, InterviewType type, int totalTurns) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.type = Objects.requireNonNull(type, "type");
        if (totalTurns <= 0) {
            throw new IllegalArgumentException("totalTurns must be positive");
        }
        this.totalTurns = totalTurns;
        this.status = SessionStatus.ACTIVE;
    }

    /**
     * 创建处于可回答状态的面试会话。
     *
     * @param totalTurns 总轮数，必须大于零
     * @return 新面试会话
     */
    public static InterviewSession start(UUID id, UUID userId, InterviewType type, int totalTurns) {
        return new InterviewSession(id, userId, type, totalTurns);
    }

    /**
     * 暂存当前轮次回答并切换到等待 AI 状态。
     * 轮次和版本必须同时匹配，避免迟到请求把旧回答写入新问题。
     *
     * @throws IllegalSessionTransitionException 会话状态、轮次或版本不匹配时抛出
     */
    public synchronized void queueAnswer(
            int turnIndex, String idempotencyKey, String answer, long expectedVersion) {
        requireVersion(expectedVersion);
        if (status != SessionStatus.ACTIVE || turnIndex != currentTurnIndex) {
            throw new IllegalSessionTransitionException("interview_answer_out_of_order");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey and answer are required");
        }
        pendingIdempotencyKey = idempotencyKey;
        pendingAnswer = answer;
        status = SessionStatus.AWAITING_AI;
        version++;
    }

    /**
     * 应用 Worker 结果并进入下一轮或完成会话。
     *
     * @param expectedVersion Worker 领取任务时看到的会话版本
     * @param nextQuestion 非最后一轮必须提供的下一问题
     * @param completed 是否结束面试
     * @throws IllegalSessionTransitionException 状态或版本已变化、下一问题缺失时抛出
     */
    public synchronized void applyAiResult(long expectedVersion, String nextQuestion, boolean completed) {
        requireVersion(expectedVersion);
        if (status != SessionStatus.AWAITING_AI) {
            throw new IllegalSessionTransitionException("interview_not_awaiting_ai");
        }
        if (completed) {
            status = SessionStatus.COMPLETED;
        } else {
            if (currentTurnIndex + 1 >= totalTurns || nextQuestion == null || nextQuestion.isBlank()) {
                throw new IllegalSessionTransitionException("next_question_required");
            }
            currentTurnIndex++;
            currentQuestion = nextQuestion;
            pendingIdempotencyKey = null;
            pendingAnswer = null;
            status = SessionStatus.ACTIVE;
        }
        version++;
    }

    private void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new IllegalSessionTransitionException("interview_version_stale");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public InterviewType type() {
        return type;
    }

    public synchronized SessionStatus status() {
        return status;
    }

    public synchronized int currentTurnIndex() {
        return currentTurnIndex;
    }

    public int totalTurns() {
        return totalTurns;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized String currentQuestion() {
        return currentQuestion;
    }

    public synchronized String pendingIdempotencyKey() {
        return pendingIdempotencyKey;
    }

    public synchronized String pendingAnswer() {
        return pendingAnswer;
    }
}
