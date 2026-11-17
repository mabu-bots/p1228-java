package p1228;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// ===== 1. GatewayClient (Discord Gateway WebSocket クライアント) =====
// java.net.http.WebSocket で Discord Gateway プロトコルを実装する。
// Hello → Heartbeat / Identify (watching を activity type 3 で送信) /
// Resume (session_id + seq) / Dispatch 配送 / 再接続バックオフ。
// 注意: この実行VMでは Java プロセスからの直接TCPが権限で遮断されるため
// 実接続は未検証 (原本も Discord 実接続は未検証)。構造は完成させてある。
public final class GatewayClient {

    // ----- 1.1 型定義と状態 -----
    public record GatewayEvent(String eventId, String eventType, Map<String, Object> payload, long sequence) {}

    public interface EventListener {
        void onEvent(GatewayEvent event);
    }

    private final Config.Bot bot;
    private final EventListener listener;
    private final Consumer<String> logger;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gateway-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket webSocket;
    private volatile boolean running = false;
    private volatile long sequence = -1;
    private volatile String sessionId = null;
    private volatile String resumeGatewayUrl = null;
    private volatile String botUserId = "";
    private volatile int reconnectAttempts = 0;
    private String gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";

    public GatewayClient(Config.Bot bot, EventListener listener, Consumer<String> logger) {
        this.bot = bot;
        this.listener = listener;
        this.logger = logger != null ? logger : System.out::println;
        this.httpClient = ProxySupport.newClient(Duration.ofSeconds(10));
    }

    public String sessionId() {
        return sessionId;
    }

    public long sequence() {
        return sequence;
    }

    public String botUserId() {
        return botUserId;
    }

    public boolean isRunning() {
        return running;
    }

    // ===== 2. 接続ライフサイクル =====
    public void start() {
        running = true;
        connect(resumeGatewayUrl != null ? resumeGatewayUrl : gatewayUrl);
    }

    public void stop() {
        running = false;
        WebSocket ws = webSocket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        scheduler.shutdownNow();
    }

    private void connect(String url) {
        if (!running) return;
        logger.accept("[gateway:" + bot.id + "] connecting to " + url);
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(url), new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        logger.accept("[gateway:" + bot.id + "] connect failed: " + error.getMessage());
                        scheduleReconnect();
                    } else {
                        webSocket = ws;
                    }
                });
    }

    private void scheduleReconnect() {
        if (!running) return;
        long delaySeconds = Math.min(30, 1L << Math.min(reconnectAttempts, 5));
        reconnectAttempts++;
        logger.accept("[gateway:" + bot.id + "] reconnecting in " + delaySeconds + "s");
        scheduler.schedule(() -> connect(sessionId != null && resumeGatewayUrl != null ? resumeGatewayUrl : gatewayUrl),
                delaySeconds, TimeUnit.SECONDS);
    }

    private void send(Map<String, Object> payload) {
        WebSocket ws = webSocket;
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendText(Json.stringify(payload), true);
        }
    }

    // ----- 2.1 Identify / Resume / Heartbeat -----
    private void sendIdentify() {
        Map<String, Object> presenceActivity = new LinkedHashMap<>();
        presenceActivity.put("name", bot.watching);
        presenceActivity.put("type", 3L); // 3 = Watching
        Map<String, Object> presence = new LinkedHashMap<>();
        presence.put("activities", List.of(presenceActivity));
        presence.put("status", "online");
        presence.put("afk", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("os", System.getProperty("os.name", "linux"));
        properties.put("browser", "p1228-java");
        properties.put("device", "p1228-java");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", bot.token);
        d.put("intents", bot.intents);
        d.put("properties", properties);
        d.put("presence", presence);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 2L);
        payload.put("d", d);
        send(payload);
        logger.accept("[gateway:" + bot.id + "] identify sent (intents=" + bot.intents + ")");
    }

    private void sendResume() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", bot.token);
        d.put("session_id", sessionId);
        d.put("seq", sequence);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 6L);
        payload.put("d", d);
        send(payload);
        logger.accept("[gateway:" + bot.id + "] resume sent (session=" + sessionId + ", seq=" + sequence + ")");
    }

    private void startHeartbeat(long intervalMillis) {
        scheduler.scheduleAtFixedRate(() -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("op", 1L);
            payload.put("d", sequence < 0 ? null : sequence);
            send(payload);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    // Voice State Update (op 4)。読み上げBOTの /join が使う。
    public void updateVoiceState(String guildId, String channelId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("guild_id", guildId);
        d.put("channel_id", channelId);
        d.put("self_mute", false);
        d.put("self_deaf", true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 4L);
        payload.put("d", d);
        send(payload);
    }

    // ===== 3. 受信処理 =====
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            logger.accept("[gateway:" + bot.id + "] websocket open");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    handlePayload(Json.obj(Json.parse(text)));
                } catch (Exception e) {
                    logger.accept("[gateway:" + bot.id + "] payload error: " + e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            logger.accept("[gateway:" + bot.id + "] websocket closed: " + statusCode + " " + reason);
            if (running) scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            logger.accept("[gateway:" + bot.id + "] websocket error: " + error.getMessage());
            if (running) scheduleReconnect();
        }
    }

    private void handlePayload(Map<String, Object> payload) {
        long op = Json.lng(payload.get("op"), -1);
        Object seqValue = payload.get("s");
        if (seqValue instanceof Number n) sequence = n.longValue();
        Map<String, Object> d = Json.obj(payload.get("d"));
        if (op == 10) { // Hello
            long interval = Json.lng(d.get("heartbeat_interval"), 0);
            if (interval < 1000) {
                logger.accept("[gateway:" + bot.id + "] invalid heartbeat interval: " + interval);
                return;
            }
            reconnectAttempts = 0;
            startHeartbeat(interval);
            if (sessionId != null && resumeGatewayUrl != null) sendResume();
            else sendIdentify();
        } else if (op == 0) { // Dispatch
            String type = Json.str(payload.get("t"));
            if ("READY".equals(type)) {
                sessionId = Json.str(d.get("session_id"));
                resumeGatewayUrl = Json.str(d.get("resume_gateway_url"));
                Map<String, Object> user = Json.obj(d.get("user"));
                botUserId = Json.str(user.get("id"));
                logger.accept("[gateway:" + bot.id + "] READY session=" + sessionId + " user=" + botUserId);
            } else if ("RESUMED".equals(type)) {
                logger.accept("[gateway:" + bot.id + "] RESUMED session=" + sessionId);
            }
            listener.onEvent(new GatewayEvent(UUID.randomUUID().toString(), type, d, sequence));
        } else if (op == 7) { // Reconnect
            WebSocket ws = webSocket;
            if (ws != null) ws.sendClose(1000, "reconnect requested");
        } else if (op == 9) { // Invalid Session
            if (!Json.bool(payload.get("d"))) {
                sessionId = null;
                resumeGatewayUrl = null;
                sequence = -1;
            }
            WebSocket ws = webSocket;
            if (ws != null) ws.sendClose(1000, "invalid session");
        }
        // op 11 = Heartbeat ACK: 何もしない
    }
}
