package p1228;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// ===== VoiceConnection (1ギルド分の Discord Voice 接続) =====
// Voice Gateway (WebSocket v8) + UDP (IP Discovery / RTP) + DAVE を
// 1本にまとめた接続。メイン Gateway 側の VOICE_STATE_UPDATE /
// VOICE_SERVER_UPDATE で得た資格情報を使って接続する。
// 送信は発話 (WAV から変換済みの Opus フレーム列) 単位でキューに入れ、
// 20ms ペーシングで流す。playFrames は発話の送信完了までブロックする。
public final class VoiceConnection {

    private final String guildId;
    private final String channelId;
    private final String selfUserId;
    private final String mainSessionId;
    private final String voiceToken;
    private final String endpoint;
    private final Consumer<String> logger;
    private final Runnable onDisconnected;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket webSocket;
    private volatile boolean closed = false;
    private volatile boolean ready = false;
    private volatile long lastSequence = -1;
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;
    private volatile java.util.concurrent.ScheduledFuture<?> keepAliveTask;

    private DatagramChannel udp;
    private InetSocketAddress voiceAddress;
    private volatile Thread udpReceiveThread;
    private int ssrc;
    private VoiceCrypto.RtpEncryptor rtpEncryptor;
    private DaveSession daveSession;
    private volatile int negotiatedDaveVersion = -1;

    private final AtomicBoolean speaking = new AtomicBoolean(false);
    private int rtpSequence;
    private int rtpTimestamp;
    private final BlockingQueue<Utterance> utteranceQueue = new LinkedBlockingQueue<>();
    private volatile Thread pumpThread;

    private record Utterance(List<byte[]> frames, CountDownLatch done) {}

    public VoiceConnection(String guildId, String channelId, String selfUserId, String mainSessionId,
                           String endpoint, String voiceToken, Consumer<String> logger, Runnable onDisconnected) {
        this.guildId = guildId;
        this.channelId = channelId;
        this.selfUserId = selfUserId;
        this.mainSessionId = mainSessionId;
        this.endpoint = endpoint;
        this.voiceToken = voiceToken;
        this.logger = logger != null ? logger : line -> {};
        this.onDisconnected = onDisconnected != null ? onDisconnected : () -> {};
        this.httpClient = ProxySupport.newClient(Duration.ofSeconds(10));
        this.rtpSequence = ThreadLocalRandom.current().nextInt(0, 0xFFFF);
        this.rtpTimestamp = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
    }

    public String guildId() {
        return guildId;
    }

    public String channelId() {
        return channelId;
    }

    public boolean isReady() {
        return ready && !closed;
    }

    public int negotiatedDaveVersion() {
        return negotiatedDaveVersion;
    }

    // ===== 1. 接続開始 =====
    public void connect() {
        String url = "wss://" + endpoint + (endpoint.contains("?") ? "&" : "?") + "v=8";
        logger.accept("[voice] connecting guild=" + guildId + " endpoint=" + endpoint);
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(url), new VoiceListener(false))
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        logger.accept("[voice] websocket connect failed guild=" + guildId + ": " + error.getMessage());
                        teardown();
                    } else {
                        webSocket = ws;
                    }
                });
    }

    private void resume() {
        String url = "wss://" + endpoint + (endpoint.contains("?") ? "&" : "?") + "v=8";
        logger.accept("[voice] resuming guild=" + guildId);
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(url), new VoiceListener(true))
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        logger.accept("[voice] resume failed guild=" + guildId + ": " + error.getMessage());
                        teardown();
                    } else {
                        webSocket = ws;
                    }
                });
    }

    // ===== 2. 切断 =====
    public synchronized void close() {
        if (closed) return;
        closed = true;
        stopPump();
        WebSocket ws = webSocket;
        if (ws != null && !ws.isOutputClosed()) ws.sendClose(WebSocket.NORMAL_CLOSURE, "leave");
        closeUdp();
        if (daveSession != null) daveSession.close();
        scheduler.shutdownNow();
    }

    private void teardown() {
        boolean wasClosed = closed;
        close();
        if (!wasClosed) onDisconnected.run();
    }

    private void closeUdp() {
        if (keepAliveTask != null) keepAliveTask.cancel(false);
        Thread receiver = udpReceiveThread;
        if (receiver != null) receiver.interrupt();
        if (udp != null) {
            try {
                udp.close();
            } catch (Exception ignored) {
                // 終了処理のため握り潰す
            }
            udp = null;
        }
    }

    // ===== 3. Voice WebSocket 受信 =====
    private final class VoiceListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();
        private final List<ByteBuffer> binaryChunks = new ArrayList<>();
        private final boolean resuming;

        VoiceListener(boolean resuming) {
            this.resuming = resuming;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String text = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleJson(Json.obj(Json.parse(text)));
                } catch (Exception e) {
                    logger.accept("[voice] json handling failed guild=" + guildId + ": " + e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            binaryChunks.add(copy);
            if (last) {
                int total = binaryChunks.stream().mapToInt(ByteBuffer::remaining).sum();
                ByteBuffer merged = ByteBuffer.allocate(total);
                binaryChunks.forEach(merged::put);
                binaryChunks.clear();
                try {
                    handleBinary(merged.array());
                } catch (Exception e) {
                    logger.accept("[voice] binary handling failed guild=" + guildId + ": " + e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            logger.accept("[voice] websocket closed guild=" + guildId + " code=" + statusCode + " " + reason);
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            if (!closed) {
                if (ready && (statusCode == 4015 || statusCode < 4000)) {
                    ready = false;
                    resume();
                } else {
                    teardown();
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            logger.accept("[voice] websocket error guild=" + guildId + ": " + error.getMessage());
            if (!closed) teardown();
        }
    }

    private void sendJson(Map<String, Object> payload) {
        WebSocket ws = webSocket;
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendText(Json.stringify(payload), true).whenComplete((ok, error) -> {
                if (error != null) logger.accept("[voice] send failed guild=" + guildId + ": " + error.getMessage());
            });
        }
    }

    private void sendBinary(int opcode, byte[] payload) {
        WebSocket ws = webSocket;
        if (ws == null || ws.isOutputClosed()) return;
        ByteBuffer buffer = ByteBuffer.allocate(1 + payload.length);
        buffer.put((byte) opcode);
        buffer.put(payload);
        buffer.flip();
        ws.sendBinary(buffer, true).whenComplete((ok, error) -> {
            if (error != null) logger.accept("[voice] binary send failed guild=" + guildId + ": " + error.getMessage());
        });
    }

    private static Map<String, Object> packet(long op, Map<String, Object> d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", op);
        payload.put("d", d);
        return payload;
    }

    private void handleJson(Map<String, Object> payload) throws Exception {
        long op = Json.lng(payload.get("op"), -1);
        Object seqValue = payload.get("seq");
        if (seqValue instanceof Number n) lastSequence = n.longValue();
        Map<String, Object> d = Json.obj(payload.get("d"));
        if (op == 8) { // Hello
            long interval = Json.lng(d.get("heartbeat_interval"), 0);
            startHeartbeat(interval);
            if (ready || rtpEncryptor != null) {
                Map<String, Object> resumeData = new LinkedHashMap<>();
                resumeData.put("server_id", guildId);
                resumeData.put("session_id", mainSessionId);
                resumeData.put("token", voiceToken);
                resumeData.put("seq_ack", lastSequence);
                sendJson(packet(7, resumeData));
            } else {
                Map<String, Object> identify = new LinkedHashMap<>();
                identify.put("server_id", guildId);
                identify.put("user_id", selfUserId);
                identify.put("session_id", mainSessionId);
                identify.put("token", voiceToken);
                identify.put("max_dave_protocol_version", maxDaveVersion());
                sendJson(packet(0, identify));
                logger.accept("[voice] identify sent guild=" + guildId + " max_dave=" + maxDaveVersion());
            }
        } else if (op == 2) { // Ready
            ssrc = (int) Json.lng(d.get("ssrc"), 0);
            String ip = Json.str(d.get("ip"));
            int port = (int) Json.lng(d.get("port"), 0);
            List<Object> modes = Json.arr(d.get("modes"));
            String mode = chooseMode(modes);
            logger.accept("[voice] ready guild=" + guildId + " ssrc=" + ssrc + " udp=" + ip + ":" + port + " mode=" + mode);
            openUdpAndSelectProtocol(ip, port, mode);
        } else if (op == 4) { // Session Description
            onSessionDescription(d);
        } else if (op == 9) { // Resumed
            ready = true;
            logger.accept("[voice] resumed guild=" + guildId);
        } else if (op == 6) { // Heartbeat ACK
            // 送信継続の確認のみ。欠落検知はメイン Gateway と違い簡易にする。
        } else if (op == 11) { // Clients Connect
            if (daveSession != null) {
                for (Object id : Json.arr(d.get("user_ids"))) daveSession.addRecognizedUser(Json.str(id));
            }
        } else if (op == 13) { // Client Disconnect
            if (daveSession != null) daveSession.removeRecognizedUser(Json.str(d.get("user_id")));
        } else if (op == 21) { // DAVE Prepare Transition
            if (daveSession != null) {
                int transitionId = (int) Json.lng(d.get("transition_id"), -1);
                int version = (int) Json.lng(d.get("protocol_version"), 0);
                if (daveSession.onPrepareTransition(transitionId, version)) {
                    Map<String, Object> readyData = new LinkedHashMap<>();
                    readyData.put("transition_id", (long) transitionId);
                    sendJson(packet(23, readyData));
                }
            }
        } else if (op == 22) { // DAVE Execute Transition
            if (daveSession != null) daveSession.onExecuteTransition((int) Json.lng(d.get("transition_id"), -1));
        } else if (op == 24) { // DAVE Prepare Epoch
            if (daveSession != null) {
                daveSession.onPrepareEpoch(Json.lng(d.get("epoch"), -1), (int) Json.lng(d.get("protocol_version"), 0));
            }
        }
    }

    private void handleBinary(byte[] message) {
        if (message.length < 3) return;
        int sequence = ((message[0] & 0xFF) << 8) | (message[1] & 0xFF);
        lastSequence = sequence;
        int op = message[2] & 0xFF;
        byte[] payload = Arrays.copyOfRange(message, 3, message.length);
        if (daveSession == null) return;
        switch (op) {
            case 25 -> daveSession.onExternalSender(payload);
            case 27 -> daveSession.onProposals(payload);
            case 29 -> daveSession.onCommit(payload);
            case 30 -> daveSession.onWelcome(payload);
            default -> logger.accept("[voice] unhandled binary op=" + op + " guild=" + guildId);
        }
    }

    private static int maxDaveVersion() {
        try {
            return DaveNative.isAvailable() ? DaveNative.get().maxSupportedProtocolVersion() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String chooseMode(List<Object> modes) {
        List<String> offered = new ArrayList<>();
        for (Object m : modes) offered.add(Json.str(m));
        if (offered.contains(VoiceCrypto.MODE_XCHACHA)) return VoiceCrypto.MODE_XCHACHA;
        if (offered.contains(VoiceCrypto.MODE_AES_GCM)) return VoiceCrypto.MODE_AES_GCM;
        throw new IllegalStateException("no supported encryption mode offered: " + offered);
    }

    private void startHeartbeat(long intervalMillis) {
        if (intervalMillis < 1000) intervalMillis = 41_250;
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        long interval = intervalMillis;
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("t", System.currentTimeMillis());
            d.put("seq_ack", lastSequence);
            sendJson(packet(3, d));
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    // ===== 4. UDP 接続と IP Discovery =====
    private void openUdpAndSelectProtocol(String ip, int port, String mode) throws Exception {
        voiceAddress = new InetSocketAddress(ip, port);
        udp = DatagramChannel.open();
        udp.configureBlocking(false);
        udp.connect(voiceAddress);
        startUdpReceiver();
        // IP Discovery: 72B (type=1, length=70, ssrc, 残りゼロ)
        ByteBuffer discovery = ByteBuffer.allocate(72);
        discovery.putShort((short) 1);
        discovery.putShort((short) 70);
        discovery.putInt(ssrc);
        discovery.flip();
        InetSocketAddress discovered = null;
        for (int attempt = 0; attempt < 5 && discovered == null; attempt++) {
            udp.send(discovery.duplicate(), voiceAddress);
            discovered = awaitDiscoveryResponse(1000);
        }
        if (discovered == null) throw new IllegalStateException("IP discovery timed out");
        logger.accept("[voice] ip discovery guild=" + guildId + " local=" + discovered.getHostString() + ":" + discovered.getPort());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("address", discovered.getHostString());
        data.put("port", (long) discovered.getPort());
        data.put("mode", mode);
        Map<String, Object> select = new LinkedHashMap<>();
        select.put("protocol", "udp");
        select.put("data", data);
        sendJson(packet(1, select));
        // UDP キープアライブ (SFU へ定期的に何か送り続ける)
        if (keepAliveTask != null) keepAliveTask.cancel(false);
        keepAliveTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (udp != null && udp.isOpen()) {
                    ByteBuffer keepAlive = ByteBuffer.allocate(8);
                    keepAlive.putInt(ssrc);
                    keepAlive.flip();
                    udp.send(keepAlive, voiceAddress);
                }
            } catch (Exception e) {
                logger.accept("[voice] udp keepalive failed guild=" + guildId + ": " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private volatile InetSocketAddress discoveryResult;

    private InetSocketAddress awaitDiscoveryResponse(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (discoveryResult != null) {
                InetSocketAddress result = discoveryResult;
                discoveryResult = null;
                return result;
            }
            Thread.sleep(20);
        }
        return null;
    }

    private void startUdpReceiver() {
        Thread receiver = new Thread(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(2048);
            while (!closed && udp != null && udp.isOpen()) {
                try {
                    buffer.clear();
                    java.net.SocketAddress source = udp.receive(buffer);
                    if (source == null) {
                        Thread.sleep(10);
                        continue;
                    }
                    buffer.flip();
                    if (buffer.remaining() >= 74 && buffer.getShort(0) == 2) {
                        int end = 8;
                        while (end < buffer.limit() && buffer.get(end) != 0) end++;
                        byte[] ipBytes = new byte[end - 8];
                        for (int i = 0; i < ipBytes.length; i++) ipBytes[i] = buffer.get(8 + i);
                        int localPort = Short.toUnsignedInt(buffer.getShort(buffer.limit() - 2));
                        // ポートは little-endian で返る
                        localPort = ((localPort & 0xFF) << 8) | ((localPort >> 8) & 0xFF);
                        discoveryResult = new InetSocketAddress(
                                new String(ipBytes, java.nio.charset.StandardCharsets.US_ASCII), localPort);
                    }
                    // 音声受信パケットは読み捨てる (受信再生は対象外)
                } catch (Exception e) {
                    if (!closed) logger.accept("[voice] udp receive failed guild=" + guildId + ": " + e.getMessage());
                    return;
                }
            }
        }, "voice-udp-receive");
        receiver.setDaemon(true);
        receiver.start();
        udpReceiveThread = receiver;
    }

    // ===== 5. Session Description と DAVE 開始 =====
    private void onSessionDescription(Map<String, Object> d) {
        List<Object> keyValues = Json.arr(d.get("secret_key"));
        byte[] secretKey = new byte[keyValues.size()];
        for (int i = 0; i < keyValues.size(); i++) secretKey[i] = (byte) Json.lng(keyValues.get(i), 0);
        String mode = Json.str(d.get("mode"));
        rtpEncryptor = new VoiceCrypto.RtpEncryptor(mode, secretKey);
        negotiatedDaveVersion = (int) Json.lng(d.get("dave_protocol_version"), 0);
        logger.accept("[voice] session description guild=" + guildId + " mode=" + mode
                + " dave_version=" + negotiatedDaveVersion);
        if (negotiatedDaveVersion > 0 && DaveNative.isAvailable()) {
            daveSession = new DaveSession(DaveNative.get(), negotiatedDaveVersion,
                    Long.parseUnsignedLong(channelId), selfUserId, new DaveSession.Sink() {
                        @Override
                        public void sendKeyPackage(byte[] keyPackage) {
                            sendBinary(26, keyPackage);
                        }

                        @Override
                        public void sendCommitWelcome(byte[] commitWelcome) {
                            sendBinary(28, commitWelcome);
                        }

                        @Override
                        public void sendReadyForTransition(int transitionId) {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("transition_id", (long) transitionId);
                            sendJson(packet(23, data));
                        }

                        @Override
                        public void sendInvalidCommitWelcome(int transitionId) {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("transition_id", (long) transitionId);
                            sendJson(packet(31, data));
                        }
                    }, logger);
            daveSession.setSsrc(ssrc);
            daveSession.init();
        } else if (negotiatedDaveVersion > 0) {
            logger.accept("[voice] DAVE required (version=" + negotiatedDaveVersion
                    + ") but libdave is unavailable: " + DaveNative.loadError());
        }
        ready = true;
        startPump();
        logger.accept("[voice] connected guild=" + guildId + " channel=" + channelId);
    }

    // ===== 6. 送信ポンプ =====
    private void startPump() {
        if (pumpThread != null) return;
        Thread pump = new Thread(this::pumpLoop, "voice-send-pump");
        pump.setDaemon(true);
        pump.start();
        pumpThread = pump;
    }

    private void stopPump() {
        Thread pump = pumpThread;
        if (pump != null) pump.interrupt();
        pumpThread = null;
        Utterance pending;
        while ((pending = utteranceQueue.poll()) != null) pending.done().countDown();
    }

    // WAV から変換済みの Opus フレーム列を送信する。完了までブロックする。
    public void playFrames(List<byte[]> frames) throws Exception {
        if (!isReady()) throw new IllegalStateException("voice connection is not ready");
        Utterance utterance = new Utterance(frames, new CountDownLatch(1));
        utteranceQueue.add(utterance);
        long timeoutMillis = frames.size() * 20L + 30_000;
        if (!utterance.done().await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("voice playback timed out");
        }
    }

    private void pumpLoop() {
        while (!closed) {
            Utterance utterance;
            try {
                utterance = utteranceQueue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (utterance == null) continue;
            try {
                sendSpeaking(true);
                long startNanos = System.nanoTime();
                List<byte[]> frames = utterance.frames();
                for (int i = 0; i < frames.size(); i++) {
                    if (closed) break;
                    sendAudioFrame(frames.get(i));
                    paceUntil(startNanos, i + 1);
                }
                // 終端の無音フレーム (DAVE 対象外・素通し) を3つ送る
                for (int i = 0; i < 3 && !closed; i++) {
                    sendAudioFrame(AudioFrames.SILENCE_FRAME);
                    paceUntil(startNanos, frames.size() + i + 1);
                }
            } catch (Exception e) {
                logger.accept("[voice] playback failed guild=" + guildId + ": " + e.getMessage());
            } finally {
                if (utteranceQueue.isEmpty()) sendSpeaking(false);
                utterance.done().countDown();
            }
        }
    }

    private void paceUntil(long startNanos, int frameIndex) throws InterruptedException {
        long target = startNanos + frameIndex * 20_000_000L;
        long waitNanos = target - System.nanoTime();
        if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
    }

    private void sendAudioFrame(byte[] opusFrame) throws Exception {
        byte[] payload = daveSession != null ? daveSession.encrypt(opusFrame) : opusFrame;
        rtpSequence = (rtpSequence + 1) & 0xFFFF;
        rtpTimestamp += AudioFrames.FRAME_SAMPLES;
        byte[] header = VoiceCrypto.rtpHeader(rtpSequence, rtpTimestamp, ssrc);
        byte[] packet = rtpEncryptor.encryptPacket(header, payload);
        DatagramChannel channel = udp;
        if (channel != null && channel.isOpen()) {
            channel.send(ByteBuffer.wrap(packet), voiceAddress);
        }
    }

    private void sendSpeaking(boolean speak) {
        speaking.set(speak);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("speaking", speak ? 1L : 0L);
        d.put("delay", 0L);
        d.put("ssrc", (long) ssrc);
        sendJson(packet(5, d));
    }
}
