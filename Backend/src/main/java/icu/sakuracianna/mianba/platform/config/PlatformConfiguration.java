package icu.sakuracianna.mianba.platform.config;

import icu.sakuracianna.mianba.identity.hcaptcha.HcaptchaProperties;
import icu.sakuracianna.mianba.interview.material.MaterialParserProperties;
import icu.sakuracianna.mianba.interview.safety.ContentSafetyProperties;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册跨领域基础设施，并在应用启动阶段执行生产配置门禁。 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner runtimeConfigurationValidation(
            RuntimeProperties runtime,
            SecurityProperties security,
            ContentSafetyProperties contentSafety,
            InfrastructureProperties infrastructure,
            IdentityProperties identity,
            SpeechProperties speech,
            AiRuntimeProperties ai,
            MaterialParserProperties materialParser,
            HcaptchaProperties hcaptcha) {
        return arguments -> RuntimeConfigurationValidator.validate(
                runtime, security, infrastructure, identity, speech, ai, materialParser, hcaptcha, contentSafety);
    }
}
