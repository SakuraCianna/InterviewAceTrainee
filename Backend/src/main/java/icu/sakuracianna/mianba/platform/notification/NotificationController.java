package icu.sakuracianna.mianba.platform.notification;

import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 接收客户端 SSE 长连接订阅的 HTTP 控制器。 */
@RestController
@RequestMapping("/api/notifications")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class NotificationController {
    private final RealtimeNotificationService notificationService;

    public NotificationController(RealtimeNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser user) {
        return notificationService.subscribe(user.userId());
    }
}
