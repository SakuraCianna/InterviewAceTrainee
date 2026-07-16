package icu.sakuracianna.mianba.interview.material;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 独立材料解析进程的最小 HTTP 边界。
 *
 * 服务只接受经过内部 token 认证的单文件请求，正文与解析结果均不写日志。
 */
public final class MaterialParserServer implements AutoCloseable {
    static final Duration DEFAULT_PARSE_DEADLINE = Duration.ofSeconds(8);
    private static final int PARSE_TIMEOUT_EXIT_STATUS = 124;
    private static final String HEALTH_PATH = "/healthz";
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 65_536;
    private static final Set<String> EXPOSED_ERROR_CODES = Set.of(
            "unsupported_resume_format",
            "resume_magic_mismatch",
            "resume_archive_entry_limit_exceeded",
            "resume_expanded_size_limit_exceeded",
            "resume_pdf_page_limit_exceeded",
            "resume_docx_document_missing",
            "resume_parse_failed",
            "image_resume_ocr_not_configured");

    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledExecutorService watchdogScheduler;

    private MaterialParserServer(
            HttpServer server,
            ExecutorService executor,
            ScheduledExecutorService watchdogScheduler) {
        this.server = server;
        this.executor = executor;
        this.watchdogScheduler = watchdogScheduler;
    }

    /** 创建并立即启动只包含健康检查和解析端点的轻量服务。 */
    public static MaterialParserServer start(
            InetSocketAddress address,
            String token,
            MaterialParserEngine engine,
            ObjectMapper mapper) throws IOException {
        return start(address, token, engine, mapper, DEFAULT_PARSE_DEADLINE);
    }

    /** 使用显式绝对解析截止启动服务，主类据环境变量传入经过边界校验的值。 */
    static MaterialParserServer start(
            InetSocketAddress address,
            String token,
            MaterialParserEngine engine,
            ObjectMapper mapper,
            Duration parseDeadline) throws IOException {
        Objects.requireNonNull(engine, "engine");
        return start(
                address,
                token,
                engine.maxUploadBytes(),
                engine::parse,
                mapper,
                parseDeadline,
                status -> Runtime.getRuntime().halt(status));
    }

    /**
     * 注入解析操作与进程终止器的包级启动入口。
     * 测试可以观察 halt 决策而不终止测试 JVM，生产入口始终绑定 Runtime.halt。
     */
    static MaterialParserServer start(
            InetSocketAddress address,
            String token,
            int maxUploadBytes,
            MaterialParseOperation parser,
            ObjectMapper mapper,
            Duration parseDeadline,
            ProcessTerminator terminator) throws IOException {
        if (token == null || token.length() < 32 || token.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Material parser token must contain at least 32 safe characters");
        }
        if (maxUploadBytes < 1 || maxUploadBytes > MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Material parser upload limit is outside the safe range");
        }
        if (parseDeadline == null || parseDeadline.isZero() || parseDeadline.isNegative()
                || parseDeadline.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Material parser deadline is outside the safe range");
        }
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(terminator, "terminator");
        HttpServer server = HttpServer.create(address, 8);
        ExecutorService executor = Executors.newFixedThreadPool(
                2, Thread.ofPlatform().name("material-parser-http-", 0).daemon(false).factory());
        ScheduledExecutorService watchdogScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("material-parser-watchdog").daemon(true).factory());
        Semaphore parseCapacity = new Semaphore(1, true);
        byte[] expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server.createContext(HEALTH_PATH, exchange -> handleHealth(exchange));
        server.createContext(HttpMaterialParserClient.PARSE_PATH, exchange -> handleParse(
                exchange, expectedAuthorization, maxUploadBytes, parser, mapper, parseCapacity,
                parseDeadline, terminator, watchdogScheduler));
        server.setExecutor(executor);
        server.start();
        return new MaterialParserServer(server, executor, watchdogScheduler);
    }

    /** 返回实际监听端口，测试使用端口零时可据此发现系统分配值。 */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        // 先撤销尚未触发的 watchdog，正常停机不得被误判为解析超时。
        watchdogScheduler.shutdownNow();
        server.stop(0);
        executor.shutdownNow();
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendEmpty(exchange, 405);
            return;
        }
        byte[] response = "ok".getBytes(StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=us-ascii");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void handleParse(
            HttpExchange exchange,
            byte[] expectedAuthorization,
            int maxUploadBytes,
            MaterialParseOperation parser,
            ObjectMapper mapper,
            Semaphore capacity,
            Duration parseDeadline,
            ProcessTerminator terminator,
            ScheduledExecutorService watchdogScheduler) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendEmpty(exchange, 405);
            return;
        }
        if (!authorized(exchange, expectedAuthorization)) {
            sendError(exchange, mapper, 401, "unauthorized");
            return;
        }
        if (!capacity.tryAcquire()) {
            sendError(exchange, mapper, 429, "material_parser_busy");
            return;
        }
        try {
            byte[] payload = readBoundedBody(exchange, maxUploadBytes);
            String filename = decodeFilename(exchange);
            String contentType = decodeHeader(exchange, HttpMaterialParserClient.CONTENT_TYPE_HEADER, 255);
            ParsedMaterial parsed = parseWithDeadline(
                    parser, filename, contentType, payload, parseDeadline, terminator, watchdogScheduler);
            byte[] response = mapper.writeValueAsBytes(new ParserResponse(
                    parsed.text(), parsed.inspection().entries(), parsed.inspection().expandedBytes(),
                    parsed.inspection().pages()));
            if (response.length > DEFAULT_MAX_RESPONSE_BYTES) {
                sendError(exchange, mapper, 500, "internal_error");
                return;
            }
            sendJson(exchange, 200, response);
        } catch (PayloadTooLargeException exception) {
            sendError(exchange, mapper, 413, "resume_file_too_large");
        } catch (BadRequestException exception) {
            sendError(exchange, mapper, 400, "bad_request");
        } catch (UnsafeMaterialException exception) {
            String code = EXPOSED_ERROR_CODES.contains(exception.getMessage())
                    ? exception.getMessage() : "resume_parse_failed";
            sendError(exchange, mapper, 422, code);
        } catch (RuntimeException exception) {
            sendError(exchange, mapper, 500, "internal_error");
        } finally {
            capacity.release();
        }
    }

    private static ParsedMaterial parseWithDeadline(
            MaterialParseOperation parser,
            String filename,
            String contentType,
            byte[] payload,
            Duration deadline,
            ProcessTerminator terminator,
            ScheduledExecutorService watchdogScheduler) {
        ScheduledFuture<?> watchdog = watchdogScheduler.schedule(
                () -> terminator.halt(PARSE_TIMEOUT_EXIT_STATUS),
                deadline.toNanos(),
                TimeUnit.NANOSECONDS);
        try {
            return parser.parse(filename, contentType, payload);
        } finally {
            // 解析正常返回或抛出可控异常都必须撤销终止任务，避免稍后误杀健康进程。
            watchdog.cancel(false);
        }
    }

    private static boolean authorized(HttpExchange exchange, byte[] expectedAuthorization) {
        String actual = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] actualBytes = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedAuthorization, actualBytes);
    }

    private static byte[] readBoundedBody(HttpExchange exchange, int maximum) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared) > maximum) {
                    throw new PayloadTooLargeException();
                }
            } catch (NumberFormatException exception) {
                throw new BadRequestException();
            }
        }
        byte[] payload = exchange.getRequestBody().readNBytes(maximum + 1);
        if (payload.length > maximum) {
            throw new PayloadTooLargeException();
        }
        return payload;
    }

    private static String decodeFilename(HttpExchange exchange) {
        String decoded = decodeHeader(exchange, HttpMaterialParserClient.FILENAME_HEADER, 1_024);
        String safe = MaterialFilename.sanitize(decoded);
        if (safe.isBlank() || !safe.equals(decoded)) {
            throw new BadRequestException();
        }
        return safe;
    }

    private static String decodeHeader(HttpExchange exchange, String name, int maximumDecodedBytes) {
        String encoded = exchange.getRequestHeaders().getFirst(name);
        if (encoded == null || encoded.isBlank() || encoded.length() > 2_048) {
            throw new BadRequestException();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length > maximumDecodedBytes) {
                throw new BadRequestException();
            }
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
            if (value.codePoints().anyMatch(Character::isISOControl)) {
                throw new BadRequestException();
            }
            return value;
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw new BadRequestException();
        }
    }

    private static void sendError(HttpExchange exchange, ObjectMapper mapper, int status, String code)
            throws IOException {
        try {
            sendJson(exchange, status, mapper.writeValueAsBytes(Map.of("error", code)));
        } catch (JacksonException exception) {
            sendEmpty(exchange, 500);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, byte[] response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private record ParserResponse(
            String text,
            int archiveEntries,
            long expandedBytes,
            int pdfPages) {
    }

    private static final class PayloadTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BadRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}

/** 可注入的材料解析操作，避免测试依赖真实第三方解析器制造永久阻塞。 */
@FunctionalInterface
interface MaterialParseOperation {
    ParsedMaterial parse(String filename, String contentType, byte[] payload);
}

/** 到达绝对解析截止时执行不可恢复的进程终止动作。 */
@FunctionalInterface
interface ProcessTerminator {
    void halt(int status);
}
