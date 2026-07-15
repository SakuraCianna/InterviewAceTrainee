package icu.sakuracianna.mianba.identity.hcaptcha;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用固定 hCaptcha 端点校验匿名身份请求，并将上游故障收敛为稳定错误。 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public final class HttpHcaptchaVerifier implements HumanVerification {
    private static final int MAX_CAPTCHA_TOKEN_CHARS = 4_096;
    private static final Set<String> TOKEN_REJECTION_CODES = Set.of(
            "missing-input-response",
            "invalid-input-response",
            "expired-input-response",
            "already-seen-response");

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final HcaptchaProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpHcaptchaVerifier(ObjectMapper mapper, HcaptchaProperties properties) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), mapper, properties);
    }

    HttpHcaptchaVerifier(HttpClient httpClient, ObjectMapper mapper, HcaptchaProperties properties) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * 向 hCaptcha 发送一次有限表单请求；任何网络或协议异常都不会降级为验证成功。
     * 响应订阅器在接收超过 16 KiB 前取消连接，避免异常上游消耗 API 堆内存。
     */
    @Override
    public void verify(String captchaToken, String remoteIp) {
        if (!properties.enabled()) {
            return;
        }
        if (captchaToken == null || captchaToken.isBlank()
                || captchaToken.length() > MAX_CAPTCHA_TOKEN_CHARS) {
            throw verificationFailed();
        }
        if (!properties.hasCredentials()) {
            throw verificationUnavailable();
        }
        HttpRequest request = HttpRequest.newBuilder(properties.verifyUrl())
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(captchaToken, remoteIp)))
                .build();
        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(
                request, ignored -> new LimitedBodySubscriber(properties.maxResponseBytes()));
        try {
            HttpResponse<byte[]> response = future.get(
                    properties.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) {
                throw verificationUnavailable();
            }
            handleResponse(response.body());
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw verificationUnavailable();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw verificationUnavailable();
        } catch (ExecutionException exception) {
            throw verificationUnavailable();
        }
    }

    private void handleResponse(byte[] body) {
        JsonNode root;
        try {
            root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readTree(body);
        } catch (JacksonException exception) {
            throw verificationUnavailable();
        }
        JsonNode success = root == null || !root.isObject() ? null : root.get("success");
        if (success == null || !success.isBoolean()) {
            throw verificationUnavailable();
        }
        if (!success.booleanValue() && isTokenRejection(root.get("error-codes"))) {
            throw verificationFailed();
        }
        if (!success.booleanValue()) {
            // 服务端密钥、站点密钥、请求协议或未知上游错误不能伪装成用户令牌错误。
            throw verificationUnavailable();
        }
    }

    private static boolean isTokenRejection(JsonNode errors) {
        if (errors == null || !errors.isArray() || errors.isEmpty()) {
            return false;
        }
        for (JsonNode error : errors) {
            if (!error.isString() || !TOKEN_REJECTION_CODES.contains(error.stringValue())) {
                return false;
            }
        }
        return true;
    }

    private String formBody(String captchaToken, String remoteIp) {
        return "secret=" + encode(properties.secret())
                + "&response=" + encode(captchaToken)
                + "&remoteip=" + encode(remoteIp == null || remoteIp.isBlank() ? "unknown" : remoteIp)
                + "&sitekey=" + encode(properties.siteKey());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ApiException verificationFailed() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "human_verification_failed",
                "人机验证失败，请重新完成验证");
    }

    private static ApiException verificationUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "human_verification_unavailable",
                "人机验证服务暂时不可用，请稍后重试");
    }

    /** 在接收线程上增量收集有限响应，突破上限时先取消网络订阅。 */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean completed;

        private LimitedBodySubscriber(int maximum) {
            this.maximum = maximum;
            this.output = new ByteArrayOutputStream(Math.min(maximum, 8_192));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            if (subscription != null) {
                candidate.cancel();
                return;
            }
            subscription = candidate;
            candidate.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (completed) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int bytes = buffer.remaining();
                if (bytes > maximum - output.size()) {
                    completed = true;
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[bytes];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!completed) {
                completed = true;
                result.completeExceptionally(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!completed) {
                completed = true;
                result.complete(output.toByteArray());
            }
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
