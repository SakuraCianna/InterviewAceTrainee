package icu.sakuracianna.mianba.admin.web;

import icu.sakuracianna.mianba.admin.service.AdminService;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.platform.web.ValidIdempotencyKey;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端供应商配置视图；真实 API 密钥始终留在 Worker secret 中。 */
@Validated
@RestController
@RequestMapping("/api/ai-providers")
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public class ProviderController {
    private final AdminService admin;

    public ProviderController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return admin.providers();
    }

    @PutMapping("/{providerId}")
    public Map<String, Object> update(
            @PathVariable UUID providerId,
            @RequestHeader("Idempotency-Key") @ValidIdempotencyKey String idempotencyKey,
            @Valid @RequestBody ProviderCommand command,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return admin.updateProvider(principal.userId(), providerId, command.enabled(), idempotencyKey);
    }

    @PostMapping("/{providerId}/test")
    public Map<String, Object> validate(@PathVariable UUID providerId) {
        return admin.validateProvider(providerId);
    }

    /** 供应商启用状态更新请求。 */
    public record ProviderCommand(boolean enabled) {
    }
}
