package icu.sakuracianna.mianba.interview.material;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import tools.jackson.databind.ObjectMapper;

/**
 * 通过 Boot fat JAR 的 PropertiesLauncher 启动的非 Spring 材料解析入口。
 *
 * 进程只加载 JDK HTTP、Jackson、PDFBox 与材料包，不创建数据库、Redis、RabbitMQ 或 Spring 上下文。
 */
public final class MaterialParserMain {
    private static final Path DEFAULT_TOKEN_PATH = Path.of("/run/secrets/material-parser-token");

    private MaterialParserMain() {
    }

    /** 启动轻量解析服务并等待 JVM 终止信号。 */
    public static void main(String[] args) throws Exception {
        String host = environment("MIANBA_MATERIAL_PARSER_HOST", "127.0.0.1");
        int port = parsePort(environment("MIANBA_MATERIAL_PARSER_PORT", "8090"));
        int parseTimeoutSeconds = parseTimeoutSeconds(environment(
                "MIANBA_MATERIAL_PARSER_PARSE_TIMEOUT_SECONDS",
                Long.toString(MaterialParserServer.DEFAULT_PARSE_DEADLINE.toSeconds())));
        Path tokenPath = Path.of(environment(
                "MIANBA_MATERIAL_PARSER_TOKEN_FILE", DEFAULT_TOKEN_PATH.toString()));
        String token = readToken(tokenPath);
        MaterialParserEngine engine = new MaterialParserEngine(
                MaterialUploadPolicy.productionDefaults(), MaterialUploadPolicy.DEFAULT_MAX_TEXT_CHARS);
        MaterialParserServer server = MaterialParserServer.start(
                new InetSocketAddress(host, port), token, engine, new ObjectMapper(),
                Duration.ofSeconds(parseTimeoutSeconds));
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("material-parser-shutdown").unstarted(() -> {
            server.close();
            stopped.countDown();
        }));
        stopped.await();
    }

    private static String readToken(Path path) throws IOException {
        String token = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (token.length() < 32 || token.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Material parser token must contain at least 32 safe characters");
        }
        return token;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Material parser port is outside the valid range");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Material parser port must be numeric", exception);
        }
    }

    /** 将独立进程解析截止限制为 1..30 秒，生产默认使用 8 秒。 */
    static int parseTimeoutSeconds(String value) {
        try {
            int seconds = Integer.parseInt(value);
            if (seconds < 1 || seconds > 30) {
                throw new IllegalArgumentException("Material parser timeout must be between 1 and 30 seconds");
            }
            return seconds;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Material parser timeout must be numeric", exception);
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
