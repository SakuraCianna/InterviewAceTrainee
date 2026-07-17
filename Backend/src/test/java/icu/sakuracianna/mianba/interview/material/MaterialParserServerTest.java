package icu.sakuracianna.mianba.interview.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MaterialParserServerTest {

    @Test
    void validatesTokenAndRequestBodyAtTheProcessBoundary() throws Exception {
        String correctToken = "c".repeat(32);
        String wrongToken = "w".repeat(32);
        try (MaterialParserServer server = MaterialParserServer.start(
                new InetSocketAddress("127.0.0.1", 0), correctToken,
                new MaterialParserEngine(MaterialUploadPolicy.productionDefaults(), 12_000),
                new ObjectMapper())) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/internal/materials/parse");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(endpoint.resolve("/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> unauthorized = client.send(request(endpoint, wrongToken, new byte[] {'a'}),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> accepted = client.send(
                    request(endpoint, correctToken, "Java".getBytes(StandardCharsets.UTF_8)),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> oversized = client.send(
                    chunkedRequest(
                            endpoint, correctToken,
                            new byte[MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES + 1]),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> malformedFilename = client.send(
                    malformedFilenameRequest(endpoint, correctToken),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).isEqualTo("ok");
            assertThat(unauthorized.statusCode()).isEqualTo(401);
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(accepted.body()).contains("Java");
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(malformedFilename.statusCode()).isEqualTo(400);
        }
    }

    @Test
    void blockingParserTriggersInjectedProcessTerminatorAtAbsoluteDeadline() throws Exception {
        String token = "c".repeat(32);
        CountDownLatch parserEntered = new CountDownLatch(1);
        CountDownLatch releaseParser = new CountDownLatch(1);
        CountDownLatch terminated = new CountDownLatch(1);
        AtomicInteger exitStatus = new AtomicInteger(-1);
        try (MaterialParserServer server = MaterialParserServer.start(
                new InetSocketAddress("127.0.0.1", 0), token,
                MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES,
                (filename, contentType, payload) -> {
                    parserEntered.countDown();
                    try {
                        releaseParser.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Parser test interrupted", exception);
                    }
                    return new ParsedMaterial("done", ArchiveInspection.none());
                },
                new ObjectMapper(), Duration.ofMillis(60), status -> {
                    exitStatus.set(status);
                    terminated.countDown();
                })) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/internal/materials/parse");
            HttpClient client = HttpClient.newHttpClient();
            var response = client.sendAsync(
                    request(endpoint, token, "Java".getBytes(StandardCharsets.UTF_8)),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(parserEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(terminated.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(exitStatus.get()).isEqualTo(124);

            releaseParser.countDown();
            assertThat(response.get(1, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            releaseParser.countDown();
        }
    }

    @Test
    void successfulParserCancelsWatchdogBeforeDeadline() throws Exception {
        String token = "c".repeat(32);
        CountDownLatch terminated = new CountDownLatch(1);
        try (MaterialParserServer server = MaterialParserServer.start(
                new InetSocketAddress("127.0.0.1", 0), token,
                MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES,
                (filename, contentType, payload) ->
                        new ParsedMaterial("done", ArchiveInspection.none()),
                new ObjectMapper(), Duration.ofMillis(80), status -> terminated.countDown())) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/internal/materials/parse");

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request(endpoint, token, "Java".getBytes(StandardCharsets.UTF_8)),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(terminated.await(200, TimeUnit.MILLISECONDS)).isFalse();
        }
    }

    private static HttpRequest request(URI endpoint, String token, byte[] body) {
        String filename = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("resume.txt".getBytes(StandardCharsets.UTF_8));
        String contentType = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("text/plain".getBytes(StandardCharsets.US_ASCII));
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token)
                .header("X-Mianba-Filename", filename)
                .header("X-Mianba-Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private static HttpRequest malformedFilenameRequest(URI endpoint, String token) {
        String malformedUtf8 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[] {(byte) 0xc3, 0x28});
        String contentType = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("text/plain".getBytes(StandardCharsets.US_ASCII));
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token)
                .header("X-Mianba-Filename", malformedUtf8)
                .header("X-Mianba-Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {'a'}))
                .build();
    }

    private static HttpRequest chunkedRequest(URI endpoint, String token, byte[] body) {
        String filename = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("resume.txt".getBytes(StandardCharsets.UTF_8));
        String contentType = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("text/plain".getBytes(StandardCharsets.US_ASCII));
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .header("X-Mianba-Filename", filename)
                .header("X-Mianba-Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
    }
}
