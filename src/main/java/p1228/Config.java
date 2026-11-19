package p1228;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ===== 1. Config (gateway.yaml 相当の config.json 読み込み) =====
// 原本 Go 版 gateway/internal/config/config.go の検証規則を移植する。
// strict=true (run用): Token / Worker Secret の実在を必須とする。
// strict=false (lenient, commands/test等のオフライン用): 存在チェックは
// 行わないが、構造検証・重複チェック・長さチェックは可能な範囲で行う。
public final class Config {

    // ----- 1.1 型定義 -----
    public static final class Gateway {
        public String listenAddress = "127.0.0.1";
        public int listenPort = 8787;
        public int eventLeaseSeconds = 30;
        public int maxEventAttempts = 8;
        public int interactionTtlSeconds = 900;
        public int eventPayloadRetentionHours = 24;
    }

    public static final class Bot {
        public String id = "";
        public String displayName = "";
        public String watching = "";
        public String applicationIdRaw = "";
        public String applicationId = "";
        public String tokenEnv = "";
        public long intents = 0;
        public String token = "";
    }

    public static final class Worker {
        public String id = "";
        public String workerId = "";
        public String role = "";
        public String botId = "";
        public String secretEnv = "";
        public String secret = "";
    }

    public final Gateway gateway = new Gateway();
    public final Map<String, Bot> bots = new LinkedHashMap<>();
    public final Map<String, Worker> workers = new LinkedHashMap<>();
    public String databasePath = "data/store.json";
    public String logLevel = "info";
    public String logFormat = "json";
    public String voicevoxBaseUrl = "http://127.0.0.1:50021";
    public String llamaBaseUrl = "http://127.0.0.1:8080";

    public static final String[] REQUIRED_BOTS = {"general", "read_aloud_a", "read_aloud_b"};

    // 既知の差異: read-aloud 2体の intents 33409 は GUILD_MEMBERS ビットを
    // 含まない (原本 config/gateway.yaml のコメントは含むと書かれているが
    // 値が一致しない。要確認事項として原本でも未解決)。
    public static long defaultIntents(String botId) {
        return "general".equals(botId) ? 33411L : 33409L;
    }

    // ----- 1.2 .env と環境変数の解決 -----
    public static Map<String, String> loadEnv(Path configDir) {
        // 実際の環境変数を土台にし、.env は未設定のキーだけを補う
        // (putIfAbsent で読み込むため、環境変数が常に優先される)。
        Map<String, String> env = new HashMap<>(System.getenv());
        Path dotEnv = configDir == null ? Path.of(".env") : configDir.resolve(".env");
        // 設定ファイルと同じフォルダの .env を優先し、無ければカレントディレクトリの .env を見る (最初に見つかった1つだけを読む)
        for (Path candidate : new Path[]{dotEnv, Path.of(".env")}) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                try {
                    for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        int eq = t.indexOf('=');
                        if (eq <= 0) continue;
                        String key = t.substring(0, eq).trim();
                        String value = t.substring(eq + 1).trim();
                        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        env.putIfAbsent(key, value);
                    }
                } catch (IOException ignored) {
                    // .env が読めなくても環境変数だけで続行する
                }
                break;
            }
        }
        return env;
    }

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    public static String expandEnv(String value, Map<String, String> env) {
        if (value == null) return "";
        Matcher m = ENV_REF.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = env.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isPlaceholder(String value) {
        String t = value == null ? "" : value.trim().toUpperCase();
        return t.isEmpty() || t.startsWith("REPLACE_ME") || t.startsWith("REPLACE");
    }

    // ----- 1.3 読み込み -----
    public static Config load(Path path, boolean strict) throws IOException {
        return load(path, strict, loadEnv(path == null ? null : path.toAbsolutePath().getParent()));
    }

    public static Config load(Path path, boolean strict, Map<String, String> env) throws IOException {
        String text;
        if (path != null && Files.isRegularFile(path)) {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } else if (strict) {
            throw new IOException("config file not found: " + path);
        } else {
            text = defaultConfigJson();
        }
        Object parsed = Json.parse(text);
        return fromJson(Json.obj(parsed), strict, env);
    }

    public static String defaultConfigJson() {
        return """
            {
              "gateway": {"listen_address":"127.0.0.1","listen_port":8787,"event_lease_seconds":30,"max_event_attempts":8,"interaction_ttl_seconds":900,"event_payload_retention_hours":24},
              "bots": {
                "general": {"display_name":"星燈","watching":"サーバーを見守り中","application_id":"${P1228_GENERAL_APPLICATION_ID}","token_env":"P1228_GENERAL_BOT_TOKEN","intents":33411},
                "read_aloud_a": {"display_name":"言ノ葉","watching":"言葉を紡ぎ中","application_id":"${P1228_READ_ALOUD_A_APPLICATION_ID}","token_env":"P1228_READ_ALOUD_A_BOT_TOKEN","intents":33409},
                "read_aloud_b": {"display_name":"紡言","watching":"声をお届け中","application_id":"${P1228_READ_ALOUD_B_APPLICATION_ID}","token_env":"P1228_READ_ALOUD_B_BOT_TOKEN","intents":33409}
              },
              "workers": {
                "general": {"worker_id":"general","role":"general","bot_id":"general","secret_env":"P1228_GENERAL_WORKER_SECRET"},
                "read_aloud_a": {"worker_id":"read_aloud_a","role":"read-aloud","bot_id":"read_aloud_a","secret_env":"P1228_READ_ALOUD_A_WORKER_SECRET"},
                "read_aloud_b": {"worker_id":"read_aloud_b","role":"read-aloud","bot_id":"read_aloud_b","secret_env":"P1228_READ_ALOUD_B_WORKER_SECRET"}
              },
              "database": {"path":"data/store.json"},
              "logging": {"level":"info","format":"json"},
              "voicevox_base_url": "http://127.0.0.1:50021",
              "llama_base_url": "http://127.0.0.1:8080"
            }
            """;
    }

    // ----- 1.4 JSONからの構築と検証 (Go config.go 移植) -----
    public static Config fromJson(Map<String, Object> root, boolean strict, Map<String, String> env) {
        Config c = new Config();
        Map<String, Object> gw = Json.obj(root.get("gateway"));
        if (gw.containsKey("listen_address")) c.gateway.listenAddress = Json.str(gw.get("listen_address"));
        if (gw.containsKey("listen_port")) c.gateway.listenPort = Json.integer(gw.get("listen_port"), 8787);
        if (gw.containsKey("event_lease_seconds")) c.gateway.eventLeaseSeconds = Json.integer(gw.get("event_lease_seconds"), 30);
        if (gw.containsKey("max_event_attempts")) c.gateway.maxEventAttempts = Json.integer(gw.get("max_event_attempts"), 8);
        if (gw.containsKey("interaction_ttl_seconds")) c.gateway.interactionTtlSeconds = Json.integer(gw.get("interaction_ttl_seconds"), 900);
        if (gw.containsKey("event_payload_retention_hours")) c.gateway.eventPayloadRetentionHours = Json.integer(gw.get("event_payload_retention_hours"), 24);
        if (c.gateway.listenAddress.isBlank()) c.gateway.listenAddress = "127.0.0.1";
        if (c.gateway.listenPort == 0) c.gateway.listenPort = 8787;
        if (c.gateway.eventLeaseSeconds == 0) c.gateway.eventLeaseSeconds = 30;
        if (c.gateway.maxEventAttempts == 0) c.gateway.maxEventAttempts = 8;
        if (c.gateway.interactionTtlSeconds == 0) c.gateway.interactionTtlSeconds = 900;
        if (c.gateway.eventPayloadRetentionHours == 0) c.gateway.eventPayloadRetentionHours = 24;

        Map<String, Object> db = Json.obj(root.get("database"));
        if (db.containsKey("path") && !Json.str(db.get("path")).isBlank()) c.databasePath = Json.str(db.get("path"));
        Map<String, Object> logging = Json.obj(root.get("logging"));
        if (logging.containsKey("level") && !Json.str(logging.get("level")).isBlank()) c.logLevel = Json.str(logging.get("level")).toLowerCase();
        if (logging.containsKey("format") && !Json.str(logging.get("format")).isBlank()) c.logFormat = Json.str(logging.get("format")).toLowerCase();
        if (root.containsKey("voicevox_base_url") && !Json.str(root.get("voicevox_base_url")).isBlank()) {
            c.voicevoxBaseUrl = Json.str(root.get("voicevox_base_url"));
        }
        if (root.containsKey("llama_base_url") && !Json.str(root.get("llama_base_url")).isBlank()) {
            c.llamaBaseUrl = Json.str(root.get("llama_base_url"));
        }

        Map<String, Object> botsJson = Json.obj(root.get("bots"));
        for (Map.Entry<String, Object> e : botsJson.entrySet()) {
            Map<String, Object> b = Json.obj(e.getValue());
            Bot bot = new Bot();
            bot.id = e.getKey();
            bot.displayName = Json.str(b.get("display_name"));
            bot.watching = Json.str(b.get("watching"));
            bot.applicationIdRaw = Json.str(b.get("application_id"));
            bot.applicationId = expandEnv(bot.applicationIdRaw, env);
            bot.tokenEnv = Json.str(b.get("token_env"));
            bot.intents = Json.lng(b.get("intents"), 0);
            if (bot.intents == 0) bot.intents = defaultIntents(bot.id);
            bot.token = bot.tokenEnv.isBlank() ? "" : env.getOrDefault(bot.tokenEnv, "");
            c.bots.put(bot.id, bot);
        }
        Map<String, Object> workersJson = Json.obj(root.get("workers"));
        for (Map.Entry<String, Object> e : workersJson.entrySet()) {
            Map<String, Object> w = Json.obj(e.getValue());
            Worker worker = new Worker();
            worker.id = e.getKey();
            worker.workerId = Json.str(w.get("worker_id"));
            worker.role = Json.str(w.get("role"));
            worker.botId = Json.str(w.get("bot_id"));
            worker.secretEnv = Json.str(w.get("secret_env"));
            worker.secret = worker.secretEnv.isBlank() ? "" : env.getOrDefault(worker.secretEnv, "");
            c.workers.put(worker.id, worker);
        }
        c.validate(strict);
        return c;
    }

    private void validate(boolean strict) {
        if (!gateway.listenAddress.equals("127.0.0.1") && !gateway.listenAddress.equals("::1")) {
            throw new IllegalArgumentException("gateway.listen_address must be a loopback address (127.0.0.1 or ::1)");
        }
        if (gateway.listenPort < 1 || gateway.listenPort > 65535) {
            throw new IllegalArgumentException("gateway.listen_port must be between 1 and 65535");
        }
        if (gateway.eventLeaseSeconds < 5 || gateway.maxEventAttempts < 1) {
            throw new IllegalArgumentException("event_lease_seconds must be >= 5 and max_event_attempts must be >= 1");
        }
        if (gateway.interactionTtlSeconds < 1 || gateway.interactionTtlSeconds > 900) {
            throw new IllegalArgumentException("interaction_ttl_seconds must be between 1 and Discord's 900 second token lifetime");
        }
        if (gateway.eventPayloadRetentionHours < 1) {
            throw new IllegalArgumentException("event_payload_retention_hours must be at least 1");
        }
        switch (logLevel) {
            case "debug", "info", "warn", "warning", "error" -> {
                if (logLevel.equals("warning")) logLevel = "warn";
            }
            default -> throw new IllegalArgumentException("logging.level must be debug, info, warn, or error");
        }
        if (!logFormat.equals("json") && !logFormat.equals("text")) {
            throw new IllegalArgumentException("logging.format must be json or text");
        }
        if (bots.size() != 3) {
            throw new IllegalArgumentException("exactly 3 bots are required (general, read_aloud_a, read_aloud_b); got " + bots.size());
        }
        Map<String, String> seenTokens = new HashMap<>();
        Map<String, String> seenApps = new HashMap<>();
        for (String id : REQUIRED_BOTS) {
            Bot bot = bots.get(id);
            if (bot == null) throw new IllegalArgumentException("required bot definition " + id + " is missing");
            if (bot.displayName.isBlank() || bot.watching.isBlank() || bot.tokenEnv.isBlank()) {
                throw new IllegalArgumentException("bot " + id + " requires display_name, watching, and token_env");
            }
            if (bot.watching.codePointCount(0, bot.watching.length()) > 128) {
                throw new IllegalArgumentException("bots." + id + ".watching must be at most 128 characters");
            }
            if (strict) {
                if (isPlaceholder(bot.token)) {
                    throw new IllegalArgumentException("environment variable " + bot.tokenEnv + " must contain a real BOT token for " + id);
                }
                if (isPlaceholder(bot.applicationId)) {
                    throw new IllegalArgumentException("bots." + id + ".application_id must be set to the real Discord Application ID");
                }
            }
            if (!isPlaceholder(bot.token)) {
                String previous = seenTokens.put(bot.token, id);
                if (previous != null) throw new IllegalArgumentException("bots " + previous + " and " + id + " must not share a BOT token");
            }
            if (!isPlaceholder(bot.applicationId)) {
                String previous = seenApps.put(bot.applicationId, id);
                if (previous != null) throw new IllegalArgumentException("bots " + previous + " and " + id + " must not share an Application ID");
            }
        }
        if (!workers.isEmpty()) {
            if (strict && workers.size() != 3) {
                throw new IllegalArgumentException("exactly 3 worker definitions are required");
            }
            Map<String, String> seenSecrets = new HashMap<>();
            for (Worker w : workers.values()) {
                if (w.workerId.isBlank() || w.botId.isBlank() || w.secretEnv.isBlank()) {
                    throw new IllegalArgumentException("worker " + w.id + " requires worker_id, bot_id and secret_env");
                }
                if (!bots.containsKey(w.botId)) {
                    throw new IllegalArgumentException("worker " + w.id + " references unknown bot " + w.botId);
                }
                if (strict && isPlaceholder(w.secret)) {
                    throw new IllegalArgumentException("environment variable " + w.secretEnv + " is required for worker " + w.id);
                }
                if (!w.secret.isBlank()) {
                    if (w.secret.length() < 32 || isPlaceholder(w.secret)) {
                        throw new IllegalArgumentException("worker secret " + w.secretEnv + " must be a real secret of at least 32 characters");
                    }
                    String previous = seenSecrets.put(w.secret, w.id);
                    if (previous != null) throw new IllegalArgumentException("workers " + previous + " and " + w.id + " must use distinct secrets");
                }
            }
            if (strict || workers.size() == 3) {
                Map<String, String> expectedRoles = Map.of("general", "general", "read_aloud_a", "read-aloud", "read_aloud_b", "read-aloud");
                for (Map.Entry<String, String> e : expectedRoles.entrySet()) {
                    Worker w = workers.get(e.getKey());
                    if (w == null) {
                        if (strict) throw new IllegalArgumentException("required worker definition " + e.getKey() + " is missing");
                        continue;
                    }
                    if (!w.workerId.equals(e.getKey()) || !w.botId.equals(e.getKey()) || !w.role.equals(e.getValue())) {
                        throw new IllegalArgumentException("worker " + e.getKey() + " must use worker_id/bot_id=" + e.getKey() + " and role=" + e.getValue());
                    }
                }
            }
        } else if (strict) {
            throw new IllegalArgumentException("exactly 3 worker definitions are required");
        }
    }
}
