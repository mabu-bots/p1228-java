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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// ===== 1. GatewayClient (Discord Gateway WebSocket クライアント) =====
// java.net.http.WebSocket で Discord Gateway プロトコルを実装する。
// Hello → Heartbeat / Identify (watching を activity type 3 で送信) /
// Resume (session_id + seq) / Dispatch 配送 / 再接続バックオフ。
// プレゼンスは全 Bot 統一形式: Watching「/help | {遅延}ms」(遅延は
// Heartbeat の往復時間)。READY / RESUMED 後に Presence Update (op 3)
// で設定し、5分ごとに更新する。
// 注意: この実行VMでは Java プロセスからの直接TCPが権限で遮断されるため
// 実接続は未検証 (原本も Discord 実接続は未検証)。構造は完成させてある。
public final class GatewayClient {

    // ----- 型定義と状態 -----
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
    private volatile ScheduledFuture<?> heartbeatTask;
    private final HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor();
    private volatile ScheduledFuture<?> presenceTask;
    private volatile boolean sessionEstablished = false;
    // プレゼンスの定期更新間隔。遅延は Heartbeat のたびに変わるため、
    // 確立後はこの間隔で Presence Update を送り直す。
    private static final long PRESENCE_UPDATE_INTERVAL_MILLIS = 300_000;
    private volatile ScheduledFuture<?> readyWatchdogTask;
    private final SessionWatchdog sessionWatchdog = new SessionWatchdog();
    // セッション確立の制限時間。接続開始からこれを過ぎても READY /
    // RESUMED が来ない接続はサイレント停滞とみなして閉じ、既存の
    // バックオフ付き再接続に乗せる (Issue #4)。
    private static final long READY_TIMEOUT_MILLIS = 30_000;

    // ハートビートの送信とACK受信を監視し、ACKが途絶えた接続 (半死状態) を
    // 検知する (レビューM-2)。前回送信分が未ACKのまま1インターバル
    // 経過したらゾンビとみなす。あわせて、送信からACK (op 11) 受信までの
    // 往復時間を Gateway 遅延として保持し、プレゼンス表示に使う。
    static final class HeartbeatMonitor {
        private long lastSentAtMillis = -1;
        private boolean acked = true;
        private long lastLatencyMillis = -1;

        synchronized void onSend(long nowMillis) {
            lastSentAtMillis = nowMillis;
            acked = false;
        }

        synchronized void onAck() {
            acked = true;
        }

        // ACK受信時刻を受け取り、直前の送信との差分を遅延として記録する。
        synchronized void onAck(long nowMillis) {
            acked = true;
            if (lastSentAtMillis >= 0) lastLatencyMillis = nowMillis - lastSentAtMillis;
        }

        synchronized void reset() {
            lastSentAtMillis = -1;
            acked = true;
            lastLatencyMillis = -1;
        }

        // 表示用の遅延 (ms)。未測定・負値は 0 に丸める。
        synchronized long latencyMillis() {
            return Math.max(0, lastLatencyMillis);
        }

        synchronized boolean isZombie(long nowMillis, long intervalMillis) {
            return lastSentAtMillis >= 0 && !acked && nowMillis - lastSentAtMillis >= intervalMillis;
        }
    }

    // 再接続しても回復しない致命的なクローズコード (Discord の Close
    // Codes で再接続が無意味とされるもの)。トークン失効 (4004) や
    // インテント不許可 (4013 / 4014) は、設定を直すまで何度接続しても
    // 同じコードで閉じられるため、無限再接続をやめて停止する。
    static boolean isFatalCloseCode(int statusCode) {
        return switch (statusCode) {
            case 4004, 4010, 4011, 4012, 4013, 4014 -> true;
            default -> false;
        };
    }

    static String fatalCloseReason(int statusCode) {
        return switch (statusCode) {
            case 4004 -> "認証失敗 (トークン失効・無効)";
            case 4010 -> "無効なシャード";
            case 4011 -> "シャーディング必須";
            case 4012 -> "無効な API バージョン";
            case 4013 -> "無効なインテント";
            case 4014 -> "許可されていないインテント";
            default -> "不明な致命的コード";
        };
    }

    // 全 Bot 統一のプレゼンス表示名: 「/help | {遅延}ms」。
    static String presenceName(long latencyMillis) {
        return "/help | " + Math.max(0, latencyMillis) + "ms";
    }

    // Presence Update (op 3) と Identify の presence で共用する d の中身。
    // アクティビティは Watching (type 3) 固定。
    static Map<String, Object> presenceData(long latencyMillis) {
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("name", presenceName(latencyMillis));
        activity.put("type", 3L); // 3 = Watching
        Map<String, Object> presence = new LinkedHashMap<>();
        presence.put("since", null);
        presence.put("activities", List.of(activity));
        presence.put("status", "online");
        presence.put("afk", false);
        return presence;
    }

    // セッション確立フェーズ (接続→Hello→Identify/Resume→READY/RESUMED)
    // を監視する (Issue #4)。ハートビートACK監視が「確立後の死活」を
    // 見るのに対し、こちらは「確立できたか」を見る。READY が来ないまま
    // 接続だけ生き続ける状態ではACK監視は発動しないため、両方が要る。
    static final class SessionWatchdog {
        private long attemptStartedAtMillis = -1;
        private boolean established = false;

        synchronized void onAttempt(long nowMillis) {
            attemptStartedAtMillis = nowMillis;
            established = false;
        }

        synchronized void onEstablished() {
            established = true;
        }

        synchronized void reset() {
            attemptStartedAtMillis = -1;
            established = false;
        }

        synchronized boolean isExpired(long nowMillis, long timeoutMillis) {
            return attemptStartedAtMillis >= 0 && !established
                    && nowMillis - attemptStartedAtMillis >= timeoutMillis;
        }
    }
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
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

    // Gateway URL には API バージョンとエンコーディングの指定が必須。
    // READY で返る resume_gateway_url はこの指定を含まない素の URL で、
    // そのまま使うと Discord 側がセッションを認識できず、Resume が
    // Invalid Session (op 9) や無応答になって永遠に成功しない
    // (2026-11-22 の障害: RESUMED 0件のまま再接続ループ)。
    // 実験で、指定を付けた場合のみ RESUMED が返ることを確認済み。
    static String withGatewayParams(String url) {
        if (url == null || url.contains("encoding=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "v=10&encoding=json";
    }

    private void connect(String url) {
        if (!running) return;
        url = withGatewayParams(url);
        logger.accept("[gateway:" + bot.id + "] connecting to " + url);
        sessionEstablished = false;
        sessionWatchdog.onAttempt(System.currentTimeMillis());
        scheduleReadyWatchdog();
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

    // 接続開始から READY_TIMEOUT_MILLIS 経ってもセッションが確立して
    // いなければ、接続を閉じて再接続させる (Issue #4)。接続自体が
    // ハングして WebSocket がまだ無い場合は、直接再接続を予約する。
    private void scheduleReadyWatchdog() {
        ScheduledFuture<?> previous = readyWatchdogTask;
        if (previous != null) previous.cancel(false);
        readyWatchdogTask = scheduler.schedule(() -> {
            if (!sessionWatchdog.isExpired(System.currentTimeMillis(), READY_TIMEOUT_MILLIS)) return;
            logger.accept("[gateway:" + bot.id + "] READY timeout; closing to reconnect");
            WebSocket ws = webSocket;
            if (ws != null) ws.sendClose(1001, "ready timeout");
            else scheduleReconnect();
        }, READY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    // 致命的クローズで停止する際に、残っている定期タスクを止める
    // (閉じた接続への Heartbeat 送信や READY ウォッチドッグの誤発を防ぐ)。
    private void cancelScheduledTasks() {
        ScheduledFuture<?> heartbeat = heartbeatTask;
        if (heartbeat != null) heartbeat.cancel(false);
        ScheduledFuture<?> presence = presenceTask;
        if (presence != null) presence.cancel(false);
        ScheduledFuture<?> watchdog = readyWatchdogTask;
        if (watchdog != null) watchdog.cancel(false);
    }

    private void scheduleReconnect() {
        if (!running) return;
        // onClose と onError の両方から呼ばれ得るため、予約済みなら二重に予約しない
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        // 再接続間隔は指数バックオフ (1, 2, 4, 8, 16, 32 秒のあと最大60秒で頭打ち)
        long delaySeconds = Math.min(60, 1L << Math.min(reconnectAttempts, 6));
        reconnectAttempts++;
        logger.accept("[gateway:" + bot.id + "] reconnecting in " + delaySeconds + "s");
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect(sessionId != null && resumeGatewayUrl != null ? resumeGatewayUrl : gatewayUrl);
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void send(Map<String, Object> payload) {
        send(webSocket, payload);
    }

    // 受信を処理している接続そのものへ送る。再接続が重なると
    // webSocket フィールドは新しい接続に移っているため、フィールド経由で
    // 送ると Identify / Resume が旧接続へ誤送信され、応答が返らないまま
    // タイムアウトする事故になる。送信失敗は握り潰さずログに残す。
    private void send(WebSocket ws, Map<String, Object> payload) {
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendText(Json.stringify(payload), true).whenComplete((ok, error) -> {
                if (error != null) {
                    logger.accept("[gateway:" + bot.id + "] send failed: " + error.getMessage());
                }
            });
        }
    }

    // ----- Identify / Resume / Heartbeat -----
    private void sendIdentify(WebSocket ws) {
        // Identify 時点では遅延は未測定のため 0ms 表示。確立後に
        // Presence Update で実測値に置き換わる。
        Map<String, Object> presence = presenceData(heartbeatMonitor.latencyMillis());
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
        send(ws, payload);
        logger.accept("[gateway:" + bot.id + "] identify sent (intents=" + bot.intents + ")");
    }

    private void sendResume(WebSocket ws) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", bot.token);
        d.put("session_id", sessionId);
        d.put("seq", sequence);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 6L);
        payload.put("d", d);
        send(ws, payload);
        logger.accept("[gateway:" + bot.id + "] resume sent (session=" + sessionId + ", seq=" + sequence + ")");
    }

    private void startHeartbeat(WebSocket ws, long intervalMillis) {
        // Hello のたびに呼ばれるため、前回の定期タスクが残っていれば
        // 先にキャンセルする (再接続を繰り返しても多重送信にならないように)。
        ScheduledFuture<?> previous = heartbeatTask;
        if (previous != null) previous.cancel(false);
        heartbeatMonitor.reset();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            if (heartbeatMonitor.isZombie(now, intervalMillis)) {
                // ACKが1インターバル途絶えた = 接続が半死状態。閉じて
                // 再接続 (可能ならResume) させる (レビューM-2)。
                logger.accept("[gateway:" + bot.id + "] heartbeat ACK timeout; closing to reconnect");
                if (ws != null) ws.sendClose(1001, "heartbeat ack timeout");
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("op", 1L);
            payload.put("d", sequence < 0 ? null : sequence);
            send(ws, payload);
            heartbeatMonitor.onSend(now);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    // Presence Update (op 3)。現在の Gateway 遅延で統一形式のプレゼンスを
    // 送る。セッション未確立の間は送らない (Identify の presence が初期値)。
    private void sendPresenceUpdate() {
        if (!sessionEstablished) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 3L);
        payload.put("d", presenceData(heartbeatMonitor.latencyMillis()));
        send(payload);
    }

    // セッション確立 (READY / RESUMED) のたびに呼ばれる。即時に1回送り、
    // 以後は PRESENCE_UPDATE_INTERVAL_MILLIS ごとに送り直す。再接続で
    // 多重にタスクが残らないよう、前回分は先にキャンセルする。
    private void startPresenceUpdates() {
        sessionEstablished = true;
        sendPresenceUpdate();
        ScheduledFuture<?> previous = presenceTask;
        if (previous != null) previous.cancel(false);
        presenceTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                sendPresenceUpdate();
            } catch (Exception e) {
                logger.accept("[gateway:" + bot.id + "] presence update failed: " + e.getMessage());
            }
        }, PRESENCE_UPDATE_INTERVAL_MILLIS, PRESENCE_UPDATE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    // Voice State Update (op 4)。原本の /join が Gateway 経由で送る操作。
    // 単一JVM構成の現行コードに呼び出し元は無いが、原本との対応を保つため残す。
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
        // テキストフレームは分割されて届くことがあるため、last=true まで連結してから解釈する
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
                    handlePayload(ws, Json.obj(Json.parse(text)));
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
            sessionEstablished = false;
            if (running && isFatalCloseCode(statusCode)) {
                // 致命的コードは再接続しても回復しないため、この Bot の
                // 再接続だけを止める。プロセスは終了させない: 単一JVMに
                // 3体が同居しており、終了させると正常な他 Bot まで pm2 の
                // 再起動ループに巻き込まれる。検知はこの FATAL ログと、
                // Discord 上でこの Bot がオフラインになることで行う。
                running = false;
                cancelScheduledTasks();
                logger.accept("[gateway:" + bot.id + "] FATAL close code " + statusCode
                        + " (" + fatalCloseReason(statusCode) + "); 再接続を停止しました。"
                        + "トークンとインテント設定を確認してください");
                return null;
            }
            if (running) scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            logger.accept("[gateway:" + bot.id + "] websocket error: " + error.getMessage());
            sessionEstablished = false;
            if (running) scheduleReconnect();
        }
    }

    private void handlePayload(WebSocket ws, Map<String, Object> payload) {
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
            // バックオフはここではリセットしない。以前は Hello でリセットして
            // いたため、READY が来ない障害時に再接続間隔が常に1秒に戻り、
            // 30秒タイムアウト + 1秒待ちのタイトなループを回し続けていた。
            // リセットはセッション確立 (READY / RESUMED) のときだけ行う。
            startHeartbeat(ws, interval);
            if (sessionId != null && resumeGatewayUrl != null) sendResume(ws);
            else sendIdentify(ws);
        } else if (op == 0) { // Dispatch
            String type = Json.str(payload.get("t"));
            if ("READY".equals(type)) {
                sessionId = Json.str(d.get("session_id"));
                resumeGatewayUrl = Json.str(d.get("resume_gateway_url"));
                Map<String, Object> user = Json.obj(d.get("user"));
                botUserId = Json.str(user.get("id"));
                sessionWatchdog.onEstablished();
                reconnectAttempts = 0;
                logger.accept("[gateway:" + bot.id + "] READY session=" + sessionId + " user=" + botUserId);
                startPresenceUpdates();
            } else if ("RESUMED".equals(type)) {
                sessionWatchdog.onEstablished();
                reconnectAttempts = 0;
                logger.accept("[gateway:" + bot.id + "] RESUMED session=" + sessionId);
                startPresenceUpdates();
            }
            listener.onEvent(new GatewayEvent(UUID.randomUUID().toString(), type, d, sequence));
        } else if (op == 7) { // Reconnect
            if (ws != null) ws.sendClose(1000, "reconnect requested");
        } else if (op == 9) { // Invalid Session
            if (!Json.bool(payload.get("d"))) {
                sessionId = null;
                resumeGatewayUrl = null;
                sequence = -1;
            }
            if (ws != null) ws.sendClose(1000, "invalid session");
        } else if (op == 11) { // Heartbeat ACK
            heartbeatMonitor.onAck(System.currentTimeMillis());
        }
    }
}
