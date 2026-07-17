package icu.sakuracianna.mianba.identity.hcaptcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class HttpHcaptchaVerifierTest {

    @Test
    void rejectsMissingTokenButSkipsNetworkWhenFeatureIsDisabled() {
        HcaptchaProperties enabled = new HcaptchaProperties(
                true, "site-key", "server-secret", URI.create("http://127.0.0.1:1/siteverify"),
                Duration.ofMillis(50), Duration.ofMillis(50), 16_384);
        HumanVerification verifier = new HttpHcaptchaVerifier(new ObjectMapper(), enabled);

        assertThatThrownBy(() -> verifier.verify(" ", "203.0.113.19"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("human_verification_failed"));

        HcaptchaProperties disabled = new HcaptchaProperties(
                false, "", "", URI.create("http://127.0.0.1:1/siteverify"),
                Duration.ofMillis(50), Duration.ofMillis(50), 16_384);
        HumanVerification disabledVerifier = new HttpHcaptchaVerifier(new ObjectMapper(), disabled);
        disabledVerifier.verify(null, "203.0.113.19");
    }

    @Test
    void sendsAllRequiredFormFieldsAndAcceptsBooleanSuccess() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (TestServer server = TestServer.start(exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"success\":true,\"hostname\":\"app.example.com\"}");
        })) {
            HumanVerification verifier = verifier(server.uri(), Duration.ofSeconds(1), 16_384);

            verifier.verify("captcha response+/=", "203.0.113.20");

            assertThat(contentType.get()).isEqualTo("application/x-www-form-urlencoded");
            assertThat(parseForm(body.get())).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "secret", "server-secret",
                    "response", "captcha response+/=",
                    "remoteip", "203.0.113.20",
                    "sitekey", "site-key"));
        }
    }

    @Test
    void mapsRejectedChallengeToStableUnprocessableError() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(
                exchange, 200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}"))) {
            HumanVerification verifier = verifier(server.uri(), Duration.ofSeconds(1), 16_384);

            assertThatThrownBy(() -> verifier.verify("rejected-token", "203.0.113.21"))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(error.detail()).isEqualTo("human_verification_failed");
                    });
        }
    }

    @Test
    void mapsServerCredentialMismatchToUnavailableInsteadOfBlamingUserToken() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(
                exchange, 200, "{\"success\":false,\"error-codes\":[\"sitekey-secret-mismatch\"]}"))) {
            HumanVerification verifier = verifier(server.uri(), Duration.ofSeconds(1), 16_384);

            assertUnavailable(() -> verifier.verify("valid-looking-token", "203.0.113.21"));
        }
    }

    @Test
    void mapsTimeoutAndInvalidJsonToStableUnavailableError() throws Exception {
        try (TestServer timeout = TestServer.start(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"success\":true}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        })) {
            HumanVerification verifier = verifier(timeout.uri(), Duration.ofMillis(50), 16_384);
            assertUnavailable(() -> verifier.verify("slow-token", "203.0.113.22"));
        }
        try (TestServer invalid = TestServer.start(exchange -> respond(
                exchange, 200, "{\"success\":true,\"success\":false}"))) {
            HumanVerification verifier = verifier(invalid.uri(), Duration.ofSeconds(1), 16_384);
            assertUnavailable(() -> verifier.verify("duplicate-json", "203.0.113.23"));
        }
    }

    @Test
    void rejectsOversizedResponseEvenWhenConfiguredLimitIsLarger() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(
                exchange, 200, "{\"success\":true,\"padding\":\"" + "x".repeat(20_000) + "\"}"))) {
            HumanVerification verifier = verifier(server.uri(), Duration.ofSeconds(1), 100_000);

            assertUnavailable(() -> verifier.verify("large-response", "203.0.113.24"));
        }
    }

    @Test
    void mapsNonSuccessHttpStatusToUnavailableError() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(exchange, 429, "{}"))) {
            HumanVerification verifier = verifier(server.uri(), Duration.ofSeconds(1), 16_384);

            assertUnavailable(() -> verifier.verify("upstream-busy", "203.0.113.25"));
        }
    }

    @Test
    void refusesRedirectInsteadOfSendingChallengeToAnotherEndpoint() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/siteverify", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        httpServer.createContext("/redirect-target", exchange -> {
            redirectedRequests.incrementAndGet();
            respond(exchange, 200, "{\"success\":true}");
        });
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
        URI verifyUri = URI.create(
                "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/siteverify");
        try (TestServer server = new TestServer(httpServer, verifyUri)) {
            HumanVerification verifier = verifier(server.uri(), Duration.ofSeconds(1), 16_384);

            assertUnavailable(() -> verifier.verify("redirect-token", "203.0.113.26"));
            assertThat(redirectedRequests).hasValue(0);
        }
    }

    private static HumanVerification verifier(URI uri, Duration requestTimeout, int maxResponseBytes) {
        HcaptchaProperties properties = new HcaptchaProperties(
                true, "site-key", "server-secret", uri,
                Duration.ofMillis(200), requestTimeout, maxResponseBytes);
        return new HttpHcaptchaVerifier(new ObjectMapper(), properties);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new ConcurrentHashMap<>();
        Arrays.stream(body.split("&")).forEach(pair -> {
            String[] parts = pair.split("=", 2);
            fields.put(decode(parts[0]), decode(parts.length == 2 ? parts[1] : ""));
        });
        return fields;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.detail()).isEqualTo("human_verification_unavailable");
                });
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
            server.createContext("/siteverify", handler::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return new TestServer(server, URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify"));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
