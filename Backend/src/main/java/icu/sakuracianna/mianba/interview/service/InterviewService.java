package icu.sakuracianna.mianba.interview.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 面试会话应用服务，所有方法均以 userId 强制限定数据所有权。 */
public interface InterviewService {
    /**
     * 创建面试会话并扣减对应权益。
     *
     * @param idempotencyKey 创建请求幂等键
     * @return 创建或幂等重放的会话视图
     */
    InterviewView start(
            UUID userId,
            UUID sessionId,
            String interviewType,
            UUID materialId,
            String idempotencyKey);

    /** 返回用户当前唯一未结束会话。 */
    Optional<InterviewView> active(UUID userId);

    /** 查询用户拥有的指定会话，不得泄露其他用户会话是否存在。 */
    InterviewView get(UUID userId, UUID sessionId);

    /**
     * 校验语音请求对应当前等待回答轮次，并返回最小语音上下文。
     * URL 中的会话标识本身不构成授权。
     */
    SpeechContext requireSpeechContext(UUID userId, UUID sessionId);

    /** 按更新时间倒序返回有界历史记录。 */
    List<InterviewHistoryView> history(UUID userId, int limit);

    /** 启动可恢复的内容擦除流程，并立即阻断后续回答与 Worker 写回。 */
    void delete(UUID userId, UUID sessionId);

    /**
     * 接收当前轮次回答并原子创建 AI 任务与 Outbox 事件。
     *
     * @param turnIndex 客户端正在回答的轮次
     * @param idempotencyKey 回答请求幂等键
     * @return 回答受理后的会话与任务快照
     */
    AnswerAcceptance answer(
            UUID userId,
            UUID sessionId,
            String idempotencyKey,
            int turnIndex,
            String answerText,
            String requestId);
}
