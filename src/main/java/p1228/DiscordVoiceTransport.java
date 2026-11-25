package p1228;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

// ===== DiscordVoiceTransport (VoiceTransport の実転送実装) =====
// メイン Gateway への Voice State Update (op 4) を起点に、
// VOICE_STATE_UPDATE (自ユーザーの session_id) と VOICE_SERVER_UPDATE
// (endpoint / token) が揃ったら VoiceConnection を張る。
// 2体の読み上げ Bot はそれぞれ自分の DiscordVoiceTransport を持ち、
// セッションは Bot ごとに独立する (既存設計どおり)。
public final class DiscordVoiceTransport implements ReadAloud.VoiceTransport {

    // メイン Gateway への橋渡し。Main が GatewayClient へ結線する。
    public interface MainGateway {
        void updateVoiceState(String guildId, String channelId);

        String selfUserId();
    }

    private final Consumer<String> logger;
    private volatile MainGateway gateway;
    private final Map<String, VoiceConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, String> pendingChannels = new ConcurrentHashMap<>();
    private final Map<String, String> voiceSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String[]> serverUpdates = new ConcurrentHashMap<>();

    public DiscordVoiceTransport(Consumer<String> logger) {
        this.logger = logger != null ? logger : line -> {};
    }

    public void bindGateway(MainGateway gateway) {
        this.gateway = gateway;
    }

    // ===== 1. VoiceTransport 実装 =====
    @Override
    public void join(String guildId, String channelId) throws Exception {
        MainGateway gw = gateway;
        if (gw == null) throw new IllegalStateException("main gateway is not bound yet");
        leave(guildId);
        pendingChannels.put(guildId, channelId);
        gw.updateVoiceState(guildId, channelId);
        // 接続確立は非同期 (VOICE_SERVER_UPDATE 到着後)。/join の応答を
        // 長時間ブロックしないよう、最大5秒だけ待って後は背景に任せる。
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (isConnected(guildId)) return;
            if (!pendingChannels.containsKey(guildId)) return;
            Thread.sleep(50);
        }
        logger.accept("[voice] join still connecting guild=" + guildId + " channel=" + channelId);
    }

    @Override
    public void leave(String guildId) {
        pendingChannels.remove(guildId);
        voiceSessionIds.remove(guildId);
        serverUpdates.remove(guildId);
        VoiceConnection connection = connections.remove(guildId);
        if (connection != null) connection.close();
        MainGateway gw = gateway;
        if (gw != null) {
            try {
                gw.updateVoiceState(guildId, null);
            } catch (Exception e) {
                logger.accept("[voice] leave update failed guild=" + guildId + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void play(String guildId, byte[] wavAudio) throws Exception {
        VoiceConnection connection = connections.get(guildId);
        if (connection == null || !connection.isReady()) {
            throw new IllegalStateException("voice connection is not ready for guild " + guildId);
        }
        List<byte[]> frames = AudioFrames.encodeWavToOpusFrames(wavAudio);
        connection.playFrames(frames);
    }

    @Override
    public boolean isConnected(String guildId) {
        VoiceConnection connection = connections.get(guildId);
        return connection != null && connection.isReady();
    }

    public int negotiatedDaveVersion(String guildId) {
        VoiceConnection connection = connections.get(guildId);
        return connection == null ? -1 : connection.negotiatedDaveVersion();
    }

    public String channelOf(String guildId) {
        VoiceConnection connection = connections.get(guildId);
        if (connection != null) return connection.channelId();
        return pendingChannels.get(guildId);
    }

    // ===== 2. メイン Gateway イベントの受け口 =====
    // VOICE_STATE_UPDATE: 自ユーザー分だけを拾う (session_id の確保)。
    public void onVoiceStateUpdate(Map<String, Object> payload) {
        MainGateway gw = gateway;
        if (gw == null) return;
        String userId = Json.str(payload.get("user_id"));
        if (userId.isBlank() || !userId.equals(gw.selfUserId())) return;
        String guildId = Json.str(payload.get("guild_id"));
        String sessionId = Json.str(payload.get("session_id"));
        if (guildId.isBlank()) return;
        Object channelValue = payload.get("channel_id");
        if (channelValue == null) {
            // 自分が VC から外れた (切断された) 場合は接続を畳む
            VoiceConnection connection = connections.remove(guildId);
            if (connection != null) connection.close();
            pendingChannels.remove(guildId);
            return;
        }
        if (!sessionId.isBlank()) {
            voiceSessionIds.put(guildId, sessionId);
            maybeConnect(guildId);
        }
    }

    // VOICE_SERVER_UPDATE: endpoint と token が届く。
    public void onVoiceServerUpdate(Map<String, Object> payload) {
        String guildId = Json.str(payload.get("guild_id"));
        String endpoint = Json.str(payload.get("endpoint"));
        String token = Json.str(payload.get("token"));
        if (guildId.isBlank() || endpoint.isBlank() || token.isBlank()) return;
        serverUpdates.put(guildId, new String[]{endpoint, token});
        maybeConnect(guildId);
    }

    private void maybeConnect(String guildId) {
        String channelId = pendingChannels.get(guildId);
        String sessionId = voiceSessionIds.get(guildId);
        String[] server = serverUpdates.get(guildId);
        MainGateway gw = gateway;
        if (channelId == null || sessionId == null || server == null || gw == null) return;
        VoiceConnection existing = connections.get(guildId);
        if (existing != null && existing.isReady() && existing.channelId().equals(channelId)) return;
        if (existing != null) existing.close();
        VoiceConnection connection = new VoiceConnection(guildId, channelId, gw.selfUserId(), sessionId,
                server[0], server[1], logger, () -> {
                    connections.remove(guildId);
                    logger.accept("[voice] connection lost guild=" + guildId);
                });
        connections.put(guildId, connection);
        connection.connect();
    }
}
