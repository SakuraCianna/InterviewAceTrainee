package icu.sakuracianna.mianba.speech;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.interview.service.InterviewService;
import icu.sakuracianna.mianba.interview.service.SpeechContext;
import icu.sakuracianna.mianba.platform.config.SpeechProperties;
import icu.sakuracianna.mianba.platform.web.ApiException;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 在已认证浏览器与腾讯云之间桥接 16kHz、16bit、单声道 PCM 流。
 *
 * 处理器不持久化原始音频，只在连接生命周期内维护转写片段。单帧大小、总时长、
 * 上游响应大小和并发数均设置硬上限，避免恶意或异常连接耗尽 4 GB 主机资源。
 */
public final class RealtimeAsrWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeAsrWebSocketHandler.class);
    private static final int MAX_CLIENT_CONTROL_CHARS = 2_048;
    private static final int MAX_AUDIO_CHUNK_BYTES = 64 * 1_024;
    private static final int PCM_BYTES_PER_SECOND = 16_000 * 2;
    private static final int MAX_PROVIDER_MESSAGE_CHARS = 2 * 1_024 * 1_024;
    private static final int MAX_TRANSCRIPT_CHARS = 8_000;
    private static final int MAX_STABLE_SEGMENTS = 512;
    static final long START_TIMEOUT_NANOS = Duration.ofSeconds(15).toNanos();
    static final long CLIENT_IDLE_TIMEOUT_NANOS = Duration.ofSeconds(20).toNanos();
    private static final int ABSOLUTE_SESSION_GRACE_SECONDS = 30;

    private final ObjectMapper mapper;
    private final InterviewService interviews;
    private final AbuseProtection abuseProtection;
    private final SpeechProperties properties;
    private final TencentRealtimeAsrSigner signer;
    private final HttpClient httpClient;
    private final Semaphore capacity;
    private final long absoluteSessionTimeoutNanos;
    private final LongSupplier nanoTime;
    private final Map<String, BridgeState> bridges = new ConcurrentHashMap<>();
    private final Map<String, String> activeTurns = new ConcurrentHashMap<>();
    private final ScheduledExecutorService idleWatchdog;

    public RealtimeAsrWebSocketHandler(
            ObjectMapper mapper,
            InterviewService interviews,
            AbuseProtection abuseProtection,
            SpeechProperties properties,
            TencentRealtimeAsrSigner signer) {
        this(mapper, interviews, abuseProtection, properties, signer,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("asr-idle-watchdog-", 0).daemon(true).factory()),
                System::nanoTime);
    }

    RealtimeAsrWebSocketHandler(
            ObjectMapper mapper,
            InterviewService interviews,
            AbuseProtection abuseProtection,
            SpeechProperties properties,
            TencentRealtimeAsrSigner signer,
            HttpClient httpClient,
            ScheduledExecutorService idleWatchdog,
            LongSupplier nanoTime) {
        this.mapper = mapper;
        this.interviews = interviews;
        this.abuseProtection = abuseProtection;
        this.properties = properties;
        this.signer = signer;
        this.httpClient = httpClient;
        this.idleWatchdog = idleWatchdog;
        this.nanoTime = nanoTime;
        this.capacity = new Semaphore(properties.maxConcurrentSessions(), true);
        this.absoluteSessionTimeoutNanos = Duration.ofSeconds(
                Math.addExact(properties.maxSeconds(), ABSOLUTE_SESSION_GRACE_SECONDS)).toNanos();
        this.idleWatchdog.scheduleAtFixedRate(this::expireIdleConnections, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopIdleWatchdog() {
        idleWatchdog.shutdownNow();
        bridges.values().forEach(this::cleanup);
    }

    /**
     * 校验认证主体、会话归属和当前轮次，并注册单轮唯一连接。
     * 校验失败时立即关闭连接，不向客户端区分账号、会话或轮次失败原因。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AuthenticatedUser user = authenticatedUser(session);
        UUID sessionId = sessionId(session);
        if (user == null || sessionId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        SpeechContext context;
        try {
            // 服务端同时校验归属、会话状态和当前等待回答轮次，URL 中的 UUID 不能授权语音调用。
            context = interviews.requireSpeechContext(user.userId(), sessionId);
        } catch (RuntimeException exception) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String activeTurnKey = user.userId() + ":" + sessionId + ":" + context.turnIndex();
        if (activeTurns.putIfAbsent(activeTurnKey, session.getId()) != null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, 5_000, MAX_PROVIDER_MESSAGE_CHARS);
        long connectedAtNanos = nanoTime.getAsLong();
        BridgeState state = new BridgeState(
                safeSession, user.userId(), sessionId, context.turnIndex(), context.interviewType(), activeTurnKey,
                connectedAtNanos);
        bridges.put(session.getId(), state);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        BridgeState state = bridges.get(session.getId());
        if (state == null || message.getPayloadLength() > MAX_CLIENT_CONTROL_CHARS) {
            close(session, new CloseStatus(1009, "control message too large"));
            return;
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(message.getPayload());
        } catch (RuntimeException exception) {
            sendError(state, "request_validation_failed", "控制消息格式不正确");
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String type = payload.path("type").stringValue("");
        if ("start".equals(type)) {
            if (payload.path("sample_rate").asInt(0) != 16_000 || !state.started.compareAndSet(false, true)) {
                sendError(state, "request_validation_failed", "语音参数不正确");
                close(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
            state.lastClientActivityNanos = nanoTime.getAsLong();
            connectProvider(state);
            return;
        }
        if ("end".equals(type) && state.provider != null) {
            state.lastClientActivityNanos = nanoTime.getAsLong();
            state.provider.sendText("{\"type\":\"end\"}", true);
            return;
        }
        sendError(state, "request_validation_failed", "不支持的控制消息");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        BridgeState state = bridges.get(session.getId());
        int bytes = message.getPayloadLength();
        if (state == null || state.provider == null || !state.providerReady.get()) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (bytes < 1 || bytes > MAX_AUDIO_CHUNK_BYTES) {
            sendError(state, "audio_chunk_too_large", "单个音频分片过大");
            close(session, new CloseStatus(1009, "audio chunk too large"));
            return;
        }
        long total = state.audioBytes + bytes;
        if (total > (long) properties.maxSeconds() * PCM_BYTES_PER_SECOND) {
            sendError(state, "audio_duration_too_long", "单次回答已达到最长时限");
            state.provider.sendText("{\"type\":\"end\"}", true);
            return;
        }
        state.audioBytes = total;
        state.lastClientActivityNanos = nanoTime.getAsLong();
        ByteBuffer copy = ByteBuffer.allocate(bytes);
        copy.put(message.getPayload().duplicate()).flip();
        state.provider.sendBinary(copy, true);
    }

    /** 处理浏览器侧传输异常，并统一释放上游连接和并发配额。 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        LOGGER.info("Realtime ASR client transport closed session_id={}", safeSessionId(session));
        close(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * 清理连接状态；所有退出路径最终都依赖幂等释放逻辑归还容量。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        BridgeState state = bridges.get(session.getId());
        if (state != null) {
            cleanup(state);
        }
    }

    /**
     * 扫描未启动、持续静默或超过绝对墙钟上限的连接。
     * 超时清理不依赖 WebSocket close 回调，确保全局许可立即归还。
     */
    void expireIdleConnections() {
        expireIdleConnections(nanoTime.getAsLong());
    }

    void expireIdleConnections(long nowNanos) {
        for (BridgeState state : bridges.values()) {
            if (state.closed.get()) {
                continue;
            }
            long connectionAge = nowNanos - state.connectedAtNanos;
            if (!state.started.get() && connectionAge >= START_TIMEOUT_NANOS) {
                timeout(state, "asr_start_timeout", "语音连接启动超时");
                continue;
            }
            if (!state.started.get()) {
                continue;
            }
            if (connectionAge >= absoluteSessionTimeoutNanos) {
                timeout(state, "asr_session_timeout", "语音连接已达到最长时限，请重新连接");
                continue;
            }
            if (nowNanos - state.lastClientActivityNanos >= CLIENT_IDLE_TIMEOUT_NANOS) {
                timeout(state, "asr_idle_timeout", "长时间未收到音频，语音连接已释放");
            }
        }
    }

    private void connectProvider(BridgeState state) throws Exception {
        if (state.closed.get() || !state.client.isOpen()) {
            return;
        }
        try {
            Duration window = Duration.ofMinutes(10);
            abuseProtection.check("asr-user", state.userId.toString(), 6, window);
            abuseProtection.check(
                    "asr-turn",
                    state.userId + ":" + state.sessionId + ':' + state.turnIndex,
                    2,
                    window);
        } catch (ApiException exception) {
            sendError(state, exception.detail(), exception.getMessage());
            close(state.client, CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!capacity.tryAcquire()) {
            sendError(state, "asr_capacity_full", "实时语音服务繁忙，请稍后重试");
            close(state.client, new CloseStatus(1013, "capacity full"));
            return;
        }
        state.capacityAcquired.set(true);
        UUID voiceId = UUID.randomUUID();
        try {
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .buildAsync(signer.sign(voiceId.toString(), state.interviewType), new ProviderListener(state))
                    .whenComplete((provider, failure) -> {
                        if (failure != null) {
                            providerFailure(state, "asr_provider_unavailable", "实时语音服务连接失败");
                            return;
                        }
                        // JDK 上游握手异步完成；客户端可能已断开，不能留下无人持有的腾讯云连接。
                        if (state.closed.get() || !state.client.isOpen()) {
                            provider.abort();
                            release(state);
                            return;
                        }
                        state.provider = provider;
                    });
        } catch (RuntimeException exception) {
            providerFailure(state, "asr_provider_unavailable", "实时语音服务尚未配置");
        }
    }

    private final class ProviderListener implements WebSocket.Listener {
        private final BridgeState state;
        private final StringBuilder textBuffer = new StringBuilder();

        private ProviderListener(BridgeState state) {
            this.state = state;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            state.provider = webSocket;
            if (state.closed.get() || !state.client.isOpen()) {
                webSocket.abort();
                release(state);
                return;
            }
            state.providerReady.set(true);
            sendJson(state, Map.of(
                    "type", "asr_ready",
                    "session_id", state.sessionId.toString(),
                    "sample_rate", 16_000,
                    "chunk_ms", 200));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (textBuffer.length() + data.length() > MAX_PROVIDER_MESSAGE_CHARS) {
                providerFailure(state, "asr_provider_invalid_response", "实时语音服务响应过大");
                webSocket.abort();
                return null;
            }
            textBuffer.append(data);
            if (last) {
                consumeProviderPayload(state, textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!state.completed.get()) {
                providerFailure(state, "asr_provider_closed", "实时语音服务连接已结束");
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            providerFailure(state, "asr_provider_failed", "实时语音服务调用失败");
        }
    }

    private void consumeProviderPayload(BridgeState state, String rawPayload) {
        try {
            JsonNode payload = mapper.readTree(rawPayload);
            int providerCode = payload.path("code").asInt(0);
            if (providerCode != 0) {
                providerFailure(state, "asr_provider_failed", "实时语音识别失败");
                return;
            }
            JsonNode result = payload.path("result");
            String text = result.path("voice_text_str").stringValue("").trim();
            if (!text.isEmpty()) {
                if (text.length() > MAX_TRANSCRIPT_CHARS) {
                    providerFailure(state, "asr_transcript_too_long", "语音转写内容过长");
                    return;
                }
                int sliceType = result.path("slice_type").asInt(-1);
                int index = result.path("index").asInt(0);
                if (sliceType == 2) {
                    if (!state.stableSegments.containsKey(index)
                            && state.stableSegments.size() >= MAX_STABLE_SEGMENTS) {
                        providerFailure(state, "asr_transcript_too_long", "语音转写片段过多");
                        return;
                    }
                    state.stableSegments.put(index, text);
                    state.partialText = "";
                } else if (sliceType == 0 || sliceType == 1) {
                    state.partialText = text;
                }
                String transcript = transcript(state);
                if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
                    providerFailure(state, "asr_transcript_too_long", "语音转写内容过长");
                    return;
                }
                sendJson(state, Map.of("type", "asr_result", "text", transcript));
            }
            if (payload.path("final").asInt(0) == 1 && state.completed.compareAndSet(false, true)) {
                sendJson(state, Map.of("type", "asr_completed", "text", transcript(state)));
                close(state.client, CloseStatus.NORMAL);
                release(state);
            }
        } catch (RuntimeException exception) {
            providerFailure(state, "asr_provider_invalid_response", "实时语音服务响应无法解析");
        }
    }

    private static String transcript(BridgeState state) {
        StringBuilder result = new StringBuilder();
        state.stableSegments.values().forEach(segment -> appendSegment(result, segment));
        appendSegment(result, state.partialText);
        return result.toString();
    }

    private static void appendSegment(StringBuilder target, String segment) {
        if (segment == null || segment.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(' ');
        }
        target.append(segment.trim());
    }

    private void providerFailure(BridgeState state, String detail, String message) {
        if (state.completed.compareAndSet(false, true)) {
            sendError(state, detail, message);
            close(state.client, CloseStatus.SERVER_ERROR);
        }
        release(state);
    }

    private void timeout(BridgeState state, String detail, String message) {
        if (state.completed.compareAndSet(false, true)) {
            sendError(state, detail, message);
        }
        cleanup(state);
        close(state.client, CloseStatus.POLICY_VIOLATION);
    }

    private void sendError(BridgeState state, String detail, String message) {
        sendJson(state, Map.of("type", "asr_error", "detail", detail, "message", message));
    }

    private void sendJson(BridgeState state, Map<String, ?> payload) {
        try {
            if (state.client.isOpen()) {
                state.client.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (Exception exception) {
            LOGGER.info("Unable to send ASR client event session_id={}", state.sessionId);
        }
    }

    private void release(BridgeState state) {
        if (state.capacityAcquired.compareAndSet(true, false)) {
            capacity.release();
        }
    }

    private void cleanup(BridgeState state) {
        if (state.closed.compareAndSet(false, true)) {
            bridges.remove(state.client.getId(), state);
            activeTurns.remove(state.activeTurnKey, state.client.getId());
            if (state.provider != null) {
                state.provider.abort();
            }
        }
        release(state);
    }

    int availableCapacity() {
        return capacity.availablePermits();
    }

    private static AuthenticatedUser authenticatedUser(WebSocketSession session) {
        if (session.getPrincipal() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }

    private static UUID sessionId(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        int separator = path.lastIndexOf('/');
        try {
            return separator >= 0 ? UUID.fromString(path.substring(separator + 1)) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String safeSessionId(WebSocketSession session) {
        UUID sessionId = sessionId(session);
        return sessionId == null ? "unknown" : sessionId.toString();
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ignored) {
            // 连接已经不可写时无需用二次异常覆盖原始失败。
        }
    }

    private static final class BridgeState {
        private final WebSocketSession client;
        private final UUID userId;
        private final UUID sessionId;
        private final int turnIndex;
        private final String interviewType;
        private final String activeTurnKey;
        private final long connectedAtNanos;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean providerReady = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean capacityAcquired = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Map<Integer, String> stableSegments = new TreeMap<>();
        private volatile WebSocket provider;
        private volatile long audioBytes;
        private volatile long lastClientActivityNanos;
        private volatile String partialText = "";

        private BridgeState(
                WebSocketSession client,
                UUID userId,
                UUID sessionId,
                int turnIndex,
                String interviewType,
                String activeTurnKey,
                long connectedAtNanos) {
            this.client = client;
            this.userId = userId;
            this.sessionId = sessionId;
            this.turnIndex = turnIndex;
            this.interviewType = interviewType;
            this.activeTurnKey = activeTurnKey;
            this.connectedAtNanos = connectedAtNanos;
            this.lastClientActivityNanos = connectedAtNanos;
        }
    }
}
