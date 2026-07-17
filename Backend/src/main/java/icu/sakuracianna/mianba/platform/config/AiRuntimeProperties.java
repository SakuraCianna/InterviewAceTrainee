package icu.sakuracianna.mianba.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Worker 启动前需要显式验证的 AI 凭据与受控测试开关，不参与模型客户端配置。 */
@ConfigurationProperties("mianba.ai-runtime")
public record AiRuntimeProperties(String deepseekApiKey, boolean stubEnabled) {
    public AiRuntimeProperties {
        deepseekApiKey = deepseekApiKey == null ? "" : deepseekApiKey.trim();
    }
}
