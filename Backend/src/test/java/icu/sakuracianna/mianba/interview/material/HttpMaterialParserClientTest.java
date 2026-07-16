package icu.sakuracianna.mianba.interview.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpMaterialParserClientTest {

    @Test
    void reportsReadyOnlyForExactAnonymousHealthResponse() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (TestServer server = TestServer.start(exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "ok");
        })) {
            HttpMaterialParserClient client = client(server.uri(), "internal-token", Duration.ofSeconds(1));

            assertThat(client.isReady()).isTrue();
            assertThat(method.get()).isEqualTo("GET");
            assertThat(authorization.get()).isNull();
        }
    }

    @Test
    void rejectsRedirectAndNonExactHealthResponse() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer redirect = TestServer.start(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/healthz-target");
            respond(exchange, 302, "ok");
        })) {
            HttpMaterialParserClient client = client(redirect.uri(), "internal-token", Duration.ofSeconds(1));

            assertThat(client.isReady()).isFalse();
            assertThat(requests.get()).isEqualTo(1);
        }
        try (TestServer malformed = TestServer.start(exchange -> respond(exchange, 200, "ok\n"))) {
            HttpMaterialParserClient client = client(malformed.uri(), "internal-token", Duration.ofSeconds(1));

            assertThat(client.isReady()).isFalse();
        }
    }

    @Test
    void boundsHealthResponseAndDeadline() throws Exception {
        try (TestServer oversized = TestServer.start(exchange -> respond(exchange, 200, "x".repeat(17)))) {
            HttpMaterialParserClient client = client(oversized.uri(), "internal-token", Duration.ofSeconds(1));

            assertThat(client.isReady()).isFalse();
        }
        try (TestServer slow = TestServer.start(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "ok");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        })) {
            HttpMaterialParserClient client = client(slow.uri(), "internal-token", Duration.ofMillis(50));
            long startedAt = System.nanoTime();

            assertThat(client.isReady()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void sendsAuthenticatedBoundedBinaryRequestWithEncodedFilename() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> encodedFilename = new AtomicReference<>();
        AtomicReference<String> encodedContentType = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        try (TestServer server = TestServer.start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            encodedFilename.set(exchange.getRequestHeaders().getFirst("X-Mianba-Filename"));
            encodedContentType.set(exchange.getRequestHeaders().getFirst("X-Mianba-Content-Type"));
            body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200,
                    "{\"text\":\"候选人材料\",\"archiveEntries\":0,\"expandedBytes\":0,\"pdfPages\":0}");
        })) {
            HttpMaterialParserClient client = client(server.uri(), "internal-token", Duration.ofSeconds(1));

            ParsedMaterial parsed = client.parse(
                    "C:\\uploads\\候选人 简历.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.text()).isEqualTo("候选人材料");
            assertThat(authorization.get()).isEqualTo("Bearer internal-token");
            assertThat(new String(Base64.getUrlDecoder().decode(encodedFilename.get()), StandardCharsets.UTF_8))
                    .isEqualTo("候选人 简历.txt");
            assertThat(new String(Base64.getUrlDecoder().decode(encodedContentType.get()), StandardCharsets.UTF_8))
                    .isEqualTo("text/plain");
            assertThat(body.get()).containsExactly("hello".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsPayloadLargerThanFiveMebibytesBeforeNetworkCall() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(exchange, 500, "{}"))) {
            HttpMaterialParserClient client = client(server.uri(), "internal-token", Duration.ofSeconds(1));

            assertThatThrownBy(() -> client.parse(
                            "resume.txt", "text/plain", new byte[MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES + 1]))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.detail()).isEqualTo("resume_file_too_large"));
        }
    }

    @Test
    void mapsMalformedOrOversizedParserResponseToStableUnavailableError() throws Exception {
        try (TestServer malformed = TestServer.start(exchange -> respond(exchange, 200, "{\"text\":true}"))) {
            HttpMaterialParserClient client = client(malformed.uri(), "internal-token", Duration.ofSeconds(1));

            assertThatThrownBy(() -> client.parse("resume.txt", "text/plain", new byte[] {'a'}))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.detail()).isEqualTo("material_parser_invalid_response"));
        }
        try (TestServer oversized = TestServer.start(exchange -> respond(exchange, 200, "x".repeat(70_000)))) {
            HttpMaterialParserClient client = client(oversized.uri(), "internal-token", Duration.ofSeconds(1));

            assertThatThrownBy(() -> client.parse("resume.txt", "text/plain", new byte[] {'a'}))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.detail()).isEqualTo("material_parser_invalid_response"));
        }
    }

    @Test
    void mapsRequestTimeoutWithoutLeakingParserDetails() throws Exception {
        try (TestServer server = TestServer.start(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(250);
                exchange.getResponseBody().write(
                        "{\"text\":\"late\",\"archiveEntries\":0,\"expandedBytes\":0,\"pdfPages\":0}"
                                .getBytes(StandardCharsets.UTF_8));
                exchange.close();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        })) {
            HttpMaterialParserClient client = client(server.uri(), "internal-token", Duration.ofMillis(50));

            assertThatThrownBy(() -> client.parse("resume.txt", "text/plain", new byte[] {'a'}))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.detail()).isEqualTo("resume_parse_timeout"));
        }
    }

    private static HttpMaterialParserClient client(URI uri, String token, Duration requestTimeout) {
        MaterialParserProperties properties = new MaterialParserProperties(
                uri, token, Duration.ofMillis(200), requestTimeout,
                MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES, 65_536);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        return new HttpMaterialParserClient(httpClient, new ObjectMapper(), properties);
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record TestServer(HttpServer server, URI uri) implements AutoCloseable {
        static TestServer start(ExchangeHandler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/materials/parse", handler::handle);
            server.createContext("/healthz", handler::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return new TestServer(server, URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
