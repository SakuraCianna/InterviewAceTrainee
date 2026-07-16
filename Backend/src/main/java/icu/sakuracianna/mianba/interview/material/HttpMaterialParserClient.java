package icu.sakuracianna.mianba.interview.material;

import icu.sakuracianna.mianba.platform.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
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

/** 通过固定内部 HTTP 端点访问独立材料解析进程，并严格验证其有限响应。 */
@Component
@ConditionalOnProperty(name = "mianba.runtime.role", havingValue = "api", matchIfMissing = true)
public final class HttpMaterialParserClient implements MaterialParserClient {
    static final String PARSE_PATH = "/internal/materials/parse";
    static final String HEALTH_PATH = "/healthz";
    static final String FILENAME_HEADER = "X-Mianba-Filename";
    static final String CONTENT_TYPE_HEADER = "X-Mianba-Content-Type";
    private static final Duration MAX_READINESS_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_READINESS_RESPONSE_BYTES = 16;
    private static final byte[] READY_RESPONSE = "ok".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "text", "archiveEntries", "expandedBytes", "pdfPages");
    private static final Set<String> SAFE_PARSER_ERRORS = Set.of(
            "unsupported_resume_format",
            "resume_magic_mismatch",
            "resume_archive_entry_limit_exceeded",
            "resume_expanded_size_limit_exceeded",
            "resume_pdf_page_limit_exceeded",
            "resume_docx_document_missing",
            "resume_parse_failed",
            "image_resume_ocr_not_configured");

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final MaterialParserProperties properties;
    private final URI endpoint;
    private final URI healthEndpoint;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpMaterialParserClient(ObjectMapper mapper, MaterialParserProperties properties) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), mapper, properties);
    }

    HttpMaterialParserClient(
            HttpClient httpClient,
            ObjectMapper mapper,
            MaterialParserProperties properties) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.properties = properties;
        this.endpoint = URI.create(properties.normalizedBaseUrl() + PARSE_PATH);
        this.healthEndpoint = URI.create(properties.normalizedBaseUrl() + HEALTH_PATH);
    }

    /**
     * 匿名探测解析进程的最小健康端点，不发送解析 token 或请求正文。
     * 超时、重定向、超限响应和协议异常全部按未就绪处理，且不记录内部响应。
     */
    @Override
    public boolean isReady() {
        Duration timeout = properties.requestTimeout().compareTo(MAX_READINESS_TIMEOUT) > 0
                ? MAX_READINESS_TIMEOUT
                : properties.requestTimeout();
        HttpRequest request = HttpRequest.newBuilder(healthEndpoint)
                .timeout(timeout)
                .header("Accept", "text/plain")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> responseFuture = httpClient.sendAsync(
                request, ignored -> new LimitedBodySubscriber(MAX_READINESS_RESPONSE_BYTES));
        try {
            HttpResponse<byte[]> response = responseFuture.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return response.statusCode() == 200 && Arrays.equals(response.body(), READY_RESPONSE);
        } catch (TimeoutException exception) {
            responseFuture.cancel(true);
            return false;
        } catch (InterruptedException exception) {
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * 发送原始有限字节，不使用 multipart 或 JSON Base64 膨胀请求体。
     * 文件名和 MIME 均使用 Base64URL 头，避免不可信字符改变 HTTP 头语义。
     */
    @Override
    public ParsedMaterial parse(String filename, String contentType, byte[] payload) {
        if (payload.length > properties.maxUploadBytes()) {
            throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE, "resume_file_too_large", "简历文件不能超过 5 MiB");
        }
        String safeFilename = MaterialFilename.sanitize(filename);
        String safeContentType = safeContentType(contentType);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.token())
                .header(FILENAME_HEADER, encodeHeader(safeFilename))
                .header(CONTENT_TYPE_HEADER, encodeHeader(safeContentType))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        CompletableFuture<HttpResponse<byte[]>> responseFuture = httpClient.sendAsync(
                request, ignored -> new LimitedBodySubscriber(properties.maxResponseBytes()));
        try {
            HttpResponse<byte[]> response = responseFuture.get(
                    properties.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return handleResponse(response.statusCode(), response.body());
        } catch (TimeoutException exception) {
            responseFuture.cancel(true);
            throw new ApiException(
                    HttpStatus.REQUEST_TIMEOUT, "resume_parse_timeout", "材料解析超时，请精简文件后重试");
        } catch (InterruptedException exception) {
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "material_parser_interrupted", "材料解析服务暂时不可用");
        } catch (ExecutionException exception) {
            if (hasCause(exception, ResponseTooLargeException.class)) {
                throw invalidResponse();
            }
            if (hasCause(exception, HttpTimeoutException.class)) {
                throw new ApiException(
                        HttpStatus.REQUEST_TIMEOUT, "resume_parse_timeout", "材料解析超时，请精简文件后重试");
            }
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "material_parser_unavailable", "材料解析服务暂时不可用");
        }
    }

    private ParsedMaterial handleResponse(int status, byte[] body) {
        if (status == 200) {
            return parseSuccess(body);
        }
        if (status == 413) {
            throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE, "resume_file_too_large", "简历文件不能超过 5 MiB");
        }
        if (status == 422) {
            String error = parseError(body);
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, error, parserErrorMessage(error));
        }
        if (status == 429) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "material_parser_busy", "材料解析繁忙，请稍后重试");
        }
        throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "material_parser_unavailable", "材料解析服务暂时不可用");
    }

    private ParsedMaterial parseSuccess(byte[] body) {
        try {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readTree(body);
            if (root == null || !root.isObject()
                    || root.propertyNames().size() != RESPONSE_FIELDS.size()
                    || !root.propertyNames().containsAll(RESPONSE_FIELDS)) {
                throw invalidResponse();
            }
            JsonNode text = root.get("text");
            JsonNode entries = root.get("archiveEntries");
            JsonNode expandedBytes = root.get("expandedBytes");
            JsonNode pages = root.get("pdfPages");
            if (text == null || !text.isString()
                    || text.stringValue().codePointCount(0, text.stringValue().length())
                            > MaterialUploadPolicy.DEFAULT_MAX_TEXT_CHARS
                    || !validInt(entries, MaterialUploadPolicy.DEFAULT_MAX_ARCHIVE_ENTRIES)
                    || !validLong(expandedBytes, MaterialUploadPolicy.DEFAULT_MAX_EXPANDED_BYTES)
                    || !validInt(pages, MaterialUploadPolicy.DEFAULT_MAX_PDF_PAGES)) {
                throw invalidResponse();
            }
            return new ParsedMaterial(
                    text.stringValue(),
                    new ArchiveInspection(entries.intValue(), expandedBytes.longValue(), pages.intValue()));
        } catch (JacksonException exception) {
            throw invalidResponse();
        }
    }

    private String parseError(byte[] body) {
        try {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readTree(body);
            if (root == null || !root.isObject() || root.propertyNames().size() != 1
                    || !root.propertyNames().contains("error")) {
                throw invalidResponse();
            }
            JsonNode error = root.get("error");
            if (error == null || !error.isString() || !SAFE_PARSER_ERRORS.contains(error.stringValue())) {
                throw invalidResponse();
            }
            return error.stringValue();
        } catch (JacksonException exception) {
            throw invalidResponse();
        }
    }

    private static boolean validInt(JsonNode value, int maximum) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 0 && value.intValue() <= maximum;
    }

    private static boolean validLong(JsonNode value, long maximum) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0 && value.longValue() <= maximum;
    }

    private static String parserErrorMessage(String error) {
        return switch (error) {
            case "image_resume_ocr_not_configured" -> "图片简历 OCR 尚未配置";
            case "resume_parse_failed" -> "无法解析上传材料";
            default -> "上传材料未通过安全校验";
        };
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_resume_format", "上传材料未通过安全校验");
        }
        return value.strip();
    }

    private static String encodeHeader(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException invalidResponse() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "material_parser_invalid_response",
                "材料解析服务返回了无效响应");
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expected) {
        Throwable current = throwable;
        while (current != null) {
            if (expected.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 在 HttpClient 接收线程上增量收集有限响应；突破上限时先取消网络订阅再失败。
     * 不能使用 ofByteArray，因为失控的内部进程可借无限响应拖垮 API 堆。
     */
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
