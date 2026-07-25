package icu.sakuracianna.mianba.platform.notification;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 针对全系统的实时 SSE 消息推送中心，支持用户点数实时同步与报告就绪通知。 */
@Service
public class RealtimeNotificationService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeNotificationService.class);
    private static final Long DEFAULT_TIMEOUT = 30 * 60 * 1000L; // 30 分钟长连接

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(e -> removeEmitter(userId, emitter));

        // 发送初始化握手包
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("message", "Realtime SSE notification stream ready", "userId", userId.toString()), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            removeEmitter(userId, emitter);
        }

        return emitter;
    }

    public void notifyBalanceUpdate(UUID userId, int newBalance, int changeAmount, String reason) {
        sendToUser(userId, "USER_CREDIT_UPDATED", Map.of(
                "newBalance", newBalance,
                "changeAmount", changeAmount,
                "reason", reason != null ? reason : "点数变动"
        ));
    }

    public void notifyReportReady(UUID userId, UUID sessionId, String interviewType) {
        sendToUser(userId, "INTERVIEW_REPORT_READY", Map.of(
                "sessionId", sessionId.toString(),
                "interviewType", interviewType != null ? interviewType : "job"
        ));
    }

    public void notifySessionStatus(UUID userId, UUID sessionId, String status) {
        sendToUser(userId, "SESSION_STATUS_CHANGED", Map.of(
                "sessionId", sessionId.toString(),
                "status", status
        ));
    }

    private void sendToUser(UUID userId, String eventName, Object data) {
        var emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.debug("Failed to send SSE event {} to user {}: {}", eventName, userId, e.getMessage());
                removeEmitter(userId, emitter);
            }
        }
    }

    private void removeEmitter(UUID userId, SseEmitter emitter) {
        var emitters = userEmitters.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                userEmitters.remove(userId);
            }
        }
    }
}
