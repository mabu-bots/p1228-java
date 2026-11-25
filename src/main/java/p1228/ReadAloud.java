package p1228;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// ===== 1. ReadAloud (読み上げ: 設定・辞書・合成・キュー) =====
// workers/src/read-aloud/ の設定マージ・辞書適用・キュー意味論を移植する。
// 重要な境界: Discord Voice の実転送 (UDP/Opus/DAVE暗号化) は Java 標準
// ライブラリだけでは実装できないため、VoiceTransport インターフェースの
// 手前までを実装する。既定実装はログ出力のみ (LogVoiceTransport)。
// 原本リポジトリでも Voice/DAVE の E2E は未検証と明記されている。
public final class ReadAloud {

    private ReadAloud() {}

    // ----- 設定の型とマージ -----
    public static final class Settings {
        public boolean enabled = false;
        public double speed = 1.0;
        public double volume = 1.0;
        public long speakerId = 1;

        public Settings copy() {
            Settings s = new Settings();
            s.enabled = enabled;
            s.speed = speed;
            s.volume = volume;
            s.speakerId = speakerId;
            return s;
        }
    }

    // Global → Guild → User の順で適用し、後から適用するスコープの値が
    // 優先される。ただし、あるスコープに無いキーは前のスコープの値を
    // 上書きしない (原本 service.ts の getSettings と同じ意味論)。
    public static Settings mergeSettings(List<Map<String, Object>> scopesInOrder) {
        Settings result = new Settings();
        for (Map<String, Object> scope : scopesInOrder) {
            if (scope == null) continue;
            if (scope.containsKey("enabled")) result.enabled = Json.bool(scope.get("enabled"));
            if (scope.containsKey("speed")) result.speed = Json.dbl(scope.get("speed"), result.speed);
            if (scope.containsKey("volume")) result.volume = Json.dbl(scope.get("volume"), result.volume);
            if (scope.containsKey("speaker_id")) result.speakerId = Json.lng(scope.get("speaker_id"), result.speakerId);
        }
        return result;
    }

    public static Settings resolveSettings(Store store, String guildId, String userId) {
        List<Map<String, Object>> scopes = new ArrayList<>();
        Store.ReadAloudSettingEntry global = store.getReadAloudSettings("global", "global");
        scopes.add(global == null ? null : global.settings);
        Store.ReadAloudSettingEntry guild = guildId == null ? null : store.getReadAloudSettings("guild", guildId);
        scopes.add(guild == null ? null : guild.settings);
        if (userId != null && !userId.isBlank()) {
            Store.ReadAloudSettingEntry user = store.getReadAloudSettings("user", userId);
            scopes.add(user == null ? null : user.settings);
        }
        return mergeSettings(scopes);
    }

    // ----- 辞書 (長い単語を先に置換・上位スコープ優先) -----
    public static List<Store.DictionaryEntry> mergeDictionary(Store store, String guildId, String userId) {
        Map<String, Store.DictionaryEntry> byWord = new LinkedHashMap<>();
        for (Store.DictionaryEntry e : store.getDictionary("global", "global")) byWord.put(e.word(), e);
        if (guildId != null && !guildId.isBlank()) {
            for (Store.DictionaryEntry e : store.getDictionary("guild", guildId)) byWord.put(e.word(), e);
        }
        if (userId != null && !userId.isBlank()) {
            for (Store.DictionaryEntry e : store.getDictionary("user", userId)) byWord.put(e.word(), e);
        }
        List<Store.DictionaryEntry> entries = new ArrayList<>(byWord.values());
        entries.sort(Comparator.comparingInt((Store.DictionaryEntry e) -> e.word().length()).reversed()
                .thenComparing(Store.DictionaryEntry::word));
        return entries;
    }

    public static String applyDictionary(String source, List<Store.DictionaryEntry> entries) {
        String value = source;
        for (Store.DictionaryEntry entry : entries) {
            if (!entry.word().isEmpty()) value = value.replace(entry.word(), entry.reading());
        }
        return value;
    }

    // ----- 読み上げ用テキスト整形 -----
    // URL・メンション・チャンネル参照・カスタム絵文字を読み上げ向けに
    // 簡略化する (原本より簡略化した実装であることを README に明記)。
    public static String cleanTextForReading(String raw) {
        if (raw == null) return "";
        String text = raw;
        text = text.replaceAll("https?://\\S+", "URL省略");
        text = text.replaceAll("<a?:([A-Za-z0-9_]+):\\d+>", "$1");
        text = text.replaceAll("<@!?\\d+>", "");
        text = text.replaceAll("<#\\d+>", "");
        text = text.replaceAll("<@&\\d+>", "");
        text = text.replaceAll("```", "");
        text = text.replaceAll("\\s+", " ").trim();
        int[] codePoints = text.codePoints().limit(5000).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    // ===== 2. 音声合成 (VOICEVOX ENGINE 互換 API) =====
    public interface Synthesizer {
        byte[] synthesize(String text, Settings settings) throws Exception;
    }

    // POST /audio_query → speedScale/volumeScale を差し替え →
    // POST /synthesis で WAV を受け取る (engine.ts と同じ2段階)。
    public static final class VoiceVoxClient implements Synthesizer {
        private final String baseUrl;
        private final HttpClient client;

        public VoiceVoxClient(String baseUrl) {
            String normalized = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:50021" : baseUrl;
            this.baseUrl = normalized.replaceAll("/+$", "");
            this.client = ProxySupport.newClient(Duration.ofSeconds(10));
        }

        public String baseUrl() {
            return baseUrl;
        }

        @Override
        public byte[] synthesize(String text, Settings settings) throws Exception {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Audio text is empty");
            String queryUrl = baseUrl + "/audio_query?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                    + "&speaker=" + settings.speakerId;
            HttpRequest queryRequest = HttpRequest.newBuilder()
                    .uri(URI.create(queryUrl))
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> queryResponse = client.send(queryRequest, HttpResponse.BodyHandlers.ofString());
            if (queryResponse.statusCode() < 200 || queryResponse.statusCode() >= 300) {
                throw new IllegalStateException("Audio engine query failed: HTTP " + queryResponse.statusCode());
            }
            Map<String, Object> query = Json.obj(Json.parse(queryResponse.body()));
            query.put("speedScale", clamp(settings.speed, 0.5, 2.0));
            query.put("volumeScale", clamp(settings.volume, 0.0, 2.0));
            String synthesisUrl = baseUrl + "/synthesis?speaker=" + settings.speakerId;
            HttpRequest synthesisRequest = HttpRequest.newBuilder()
                    .uri(URI.create(synthesisUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(query)))
                    .build();
            HttpResponse<byte[]> synthesisResponse = client.send(synthesisRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (synthesisResponse.statusCode() < 200 || synthesisResponse.statusCode() >= 300) {
                throw new IllegalStateException("Audio engine synthesis failed: HTTP " + synthesisResponse.statusCode());
            }
            byte[] audio = synthesisResponse.body();
            if (!isWav(audio)) throw new IllegalStateException("Audio engine returned an empty or invalid WAV payload");
            return audio;
        }

        static double clamp(double value, double min, double max) {
            if (!Double.isFinite(value)) return min;
            return Math.max(min, Math.min(max, value));
        }

        static boolean isWav(byte[] audio) {
            return audio != null && audio.length >= 44
                    && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                    && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E';
        }
    }

    // ===== 3. Voice転送の境界 =====
    public interface VoiceTransport {
        void join(String guildId, String channelId) throws Exception;

        void leave(String guildId) throws Exception;

        void play(String guildId, byte[] wavAudio) throws Exception;

        boolean isConnected(String guildId);
    }

    // 既定実装: 実転送の代わりに接続状態だけを保持し、再生はログに残す。
    public static final class LogVoiceTransport implements VoiceTransport {
        private final Map<String, String> channels = new HashMap<>();
        private final Consumer<String> logger;

        public LogVoiceTransport(Consumer<String> logger) {
            this.logger = logger != null ? logger : System.out::println;
        }

        @Override
        public synchronized void join(String guildId, String channelId) {
            channels.put(guildId, channelId);
            logger.accept("[voice] join requested guild=" + guildId + " channel=" + channelId
                    + " (実転送は未実装: UDP/Opus/DAVE は標準ライブラリ対象外)");
        }

        @Override
        public synchronized void leave(String guildId) {
            channels.remove(guildId);
            logger.accept("[voice] left guild=" + guildId);
        }

        @Override
        public void play(String guildId, byte[] wavAudio) {
            logger.accept("[voice] play guild=" + guildId + " wav_bytes=" + (wavAudio == null ? 0 : wavAudio.length)
                    + " (ログ転送のため実際の再生は行われない)");
        }

        @Override
        public synchronized boolean isConnected(String guildId) {
            return channels.containsKey(guildId);
        }

        public synchronized String channelOf(String guildId) {
            return channels.get(guildId);
        }
    }

    // ===== 4. 読み上げサービス (ギルド別キュー) =====
    public static final class ReadAloudService {
        public record SpeakItem(String text, String userId) {}

        private final Store store;
        private final Synthesizer synthesizer;
        private final VoiceTransport transport;
        private final Consumer<String> logger;
        private final boolean async;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "read-aloud-pump");
            t.setDaemon(true);
            return t;
        });
        private final Map<String, Deque<SpeakItem>> queues = new HashMap<>();
        private final java.util.Set<String> processing = new java.util.HashSet<>();

        public ReadAloudService(Store store, Synthesizer synthesizer, VoiceTransport transport,
                                Consumer<String> logger, boolean async) {
            this.store = store;
            this.synthesizer = synthesizer;
            this.transport = transport;
            this.logger = logger != null ? logger : System.out::println;
            this.async = async;
        }

        public VoiceTransport transport() {
            return transport;
        }

        public synchronized int queueSize(String guildId) {
            Deque<SpeakItem> q = queues.get(guildId);
            return q == null ? 0 : q.size();
        }

        public void join(String guildId, String channelId) throws Exception {
            if (guildId == null || guildId.isBlank() || channelId == null || channelId.isBlank()) {
                throw new IllegalArgumentException("guild_id and channel_id are required");
            }
            transport.leave(guildId);
            transport.join(guildId, channelId);
        }

        public void leave(String guildId) throws Exception {
            synchronized (this) {
                Deque<SpeakItem> q = queues.get(guildId);
                if (q != null) q.clear();
            }
            transport.leave(guildId);
        }

        // 原本 speak: 未接続ならエラー、キュー上限50件、本文は5000字まで。
        public void speak(String guildId, String rawText, String userId) throws Exception {
            if (!transport.isConnected(guildId)) {
                throw new IllegalStateException("BOT is not connected to a voice channel. Use /join first.");
            }
            String text = rawText == null ? "" : rawText.trim();
            int[] cps = text.codePoints().limit(5000).toArray();
            text = new String(cps, 0, cps.length);
            if (text.isBlank()) throw new IllegalArgumentException("読み上げる文章が空です。");
            synchronized (this) {
                Deque<SpeakItem> q = queues.computeIfAbsent(guildId, g -> new ArrayDeque<>());
                if (q.size() >= 50) throw new IllegalStateException("読み上げキューが上限（50件）に達しています。");
                q.add(new SpeakItem(text, userId));
            }
            if (async) executor.submit(() -> drain(guildId));
            else drain(guildId);
        }

        // キューを1件ずつ処理する。1件の失敗で後続を止めない (原本と同方針)。
        public void drain(String guildId) {
            // 同一ギルドの drain は同時に1つだけ動かす (非同期投入が重なっても二重処理しない)
            synchronized (this) {
                if (processing.contains(guildId)) return;
                processing.add(guildId);
            }
            try {
                while (true) {
                    SpeakItem item;
                    synchronized (this) {
                        Deque<SpeakItem> q = queues.get(guildId);
                        if (q == null || q.isEmpty()) break;
                        item = q.poll();
                    }
                    try {
                        Settings settings = resolveSettings(store, guildId, item.userId());
                        List<Store.DictionaryEntry> dictionary = mergeDictionary(store, guildId, item.userId());
                        String text = applyDictionary(cleanTextForReading(item.text()), dictionary);
                        byte[] audio = synthesizer.synthesize(text, settings);
                        transport.play(guildId, audio);
                        logger.accept("[read-aloud] spoken guild=" + guildId + " text=\"" + abbreviate(text) + "\"");
                    } catch (Exception itemError) {
                        logger.accept("[read-aloud] audio_item_failed guild=" + guildId + " error=" + itemError.getMessage());
                        if (!transport.isConnected(guildId)) break;
                    }
                }
            } finally {
                synchronized (this) {
                    processing.remove(guildId);
                }
            }
        }

        // 原本 maybeReadMessage: 接続中かつ設定 enabled のときだけ読み上げる。
        public void maybeReadMessage(String guildId, String userId, String text) throws Exception {
            if (guildId == null || guildId.isBlank() || text == null || text.isBlank()) return;
            if (!transport.isConnected(guildId)) return;
            Settings settings = resolveSettings(store, guildId, userId);
            if (!settings.enabled) return;
            speak(guildId, text, userId);
        }

        private static String abbreviate(String text) {
            return text.length() <= 40 ? text : text.substring(0, 40) + "…";
        }
    }
}
