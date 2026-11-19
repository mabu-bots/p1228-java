package p1228;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// ===== 1. DiscordRest (Discord REST クライアント) =====
// 原本 Gateway の DiscordOperation 許可リストと同じ6操作だけを許可する。
// operation_id による冪等キャッシュを持ち、同じ operation_id の再送では
// Discord へ再リクエストしない。単一JVM構成のため、Interaction 応答は
// interaction_ref 経由ではなく Discord の Interaction Callback /
// Webhook エンドポイントへ直接送る (DESIGN.md 参照)。
public final class DiscordRest implements StarHandler.DiscordOps {

    // ----- 1.1 許可リストと状態 -----
    public static final Set<String> ALLOWLIST = Set.of(
            "send_message", "edit_message", "delete_message", "sync_commands", "add_role", "remove_role");

    public static final String API_BASE = "https://discord.com/api/v10";

    // 冪等キャッシュの上限。際限なく増えないよう、上限到達時は古い結果ごと
    // まとめて破棄する (重複排除できなくなるのは十分に古い operation_id だけ)。
    private static final int OPERATION_CACHE_LIMIT = 1000;

    private final String botId;
    private final String applicationId;
    private final String token;
    private final HttpClient client;
    private final Map<String, Map<String, Object>> operationCache = new ConcurrentHashMap<>();

    public DiscordRest(String botId, String applicationId, String token) {
        this.botId = botId;
        this.applicationId = applicationId;
        this.token = token;
        this.client = ProxySupport.newClient(Duration.ofSeconds(10));
    }

    public String botId() {
        return botId;
    }

    // ===== 2. 低レベルリクエスト =====
    // 429 (レート制限) のときは retry_after だけ待って1回だけ再試行する
    // (レビューM-3)。待機は最大5秒に抑え、インタラクション応答の
    // 制限時間 (初回応答3秒・トークン有効15分) を大きく圧迫しないようにする。
    static long clampRetryDelayMillis(double retryAfterSeconds) {
        if (retryAfterSeconds < 0 || Double.isNaN(retryAfterSeconds)) retryAfterSeconds = 1.0;
        return Math.min(5000L, (long) (retryAfterSeconds * 1000));
    }

    private static long retryDelayMillis(HttpResponse<String> response) {
        Object value = null;
        String text = response.body();
        if (text != null && !text.isBlank()) {
            try {
                value = Json.obj(Json.parse(text)).get("retry_after");
            } catch (Exception ignored) {
                // ボディが読めなければヘッダにフォールバックする
            }
        }
        if (value instanceof Number n) return clampRetryDelayMillis(n.doubleValue());
        var header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                return clampRetryDelayMillis(Double.parseDouble(header.get()));
            } catch (Exception ignored) {
                // 解釈できなければ既定の待機を使う
            }
        }
        return clampRetryDelayMillis(-1);
    }

    private HttpResponse<String> sendOnce(String method, String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordBot (p1228-java, 1.0)");
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> request(String method, String path, Object body) throws Exception {
        HttpResponse<String> response = sendOnce(method, path, body);
        if (response.statusCode() == 429) {
            Thread.sleep(retryDelayMillis(response));
            response = sendOnce(method, path, body);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Discord REST " + method + " " + path + " failed: HTTP " + response.statusCode());
        }
        String text = response.body();
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Object parsed = Json.parse(text);
        return parsed instanceof Map ? Json.obj(parsed) : new LinkedHashMap<>(Map.of("value", parsed));
    }

    // ===== 3. 許可リスト操作 (operation_id 冪等) =====
    public Map<String, Object> execute(String operation, Map<String, Object> fields, String operationId) throws Exception {
        if (!ALLOWLIST.contains(operation)) {
            throw new IllegalArgumentException("operation is not allowlisted: " + operation);
        }
        if (operationId != null && !operationId.isBlank()) {
            Map<String, Object> cached = operationCache.get(operationId);
            if (cached != null) return cached;
        }
        Map<String, Object> result = switch (operation) {
            case "send_message" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("content", Json.str(fields.get("content")));
                if (fields.get("components") != null) body.put("components", fields.get("components"));
                yield request("POST", "/channels/" + Json.str(fields.get("channel_id")) + "/messages", body);
            }
            // edit_message は DiscordOps 経由の呼び出し元が現状無いが、
            // 原本の許可リスト (6操作) と一致させるため残している。
            case "edit_message" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("content", Json.str(fields.get("content")));
                yield request("PATCH", "/channels/" + Json.str(fields.get("channel_id")) + "/messages/" + Json.str(fields.get("message_id")), body);
            }
            case "delete_message" -> request("DELETE",
                    "/channels/" + Json.str(fields.get("channel_id")) + "/messages/" + Json.str(fields.get("message_id")), null);
            case "sync_commands" -> request("PUT",
                    "/applications/" + Json.str(fields.get("application_id")) + "/commands", fields.get("commands"));
            case "add_role" -> request("PUT",
                    "/guilds/" + Json.str(fields.get("guild_id")) + "/members/" + Json.str(fields.get("user_id")) + "/roles/" + Json.str(fields.get("role_id")), null);
            case "remove_role" -> request("DELETE",
                    "/guilds/" + Json.str(fields.get("guild_id")) + "/members/" + Json.str(fields.get("user_id")) + "/roles/" + Json.str(fields.get("role_id")), null);
            default -> throw new IllegalArgumentException("operation is not allowlisted: " + operation);
        };
        if (operationId != null && !operationId.isBlank()) {
            if (operationCache.size() >= OPERATION_CACHE_LIMIT) operationCache.clear();
            operationCache.put(operationId, result);
        }
        return result;
    }

    public List<Object> syncCommands(String appId, List<Map<String, Object>> commands, String operationId) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("application_id", appId);
        fields.put("commands", commands);
        Map<String, Object> result = execute("sync_commands", fields, operationId);
        Object value = result.get("value");
        return value instanceof List ? Json.arr(value) : List.of(result);
    }

    // ===== 4. DiscordOps 実装 =====
    @Override
    public String sendMessage(String channelId, String content, Object components, String operationId) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("channel_id", channelId);
        fields.put("content", content);
        if (components != null) fields.put("components", components);
        Map<String, Object> result = execute("send_message", fields, operationId);
        return Json.str(result.get("id"));
    }

    @Override
    public void deleteMessage(String channelId, String messageId, String operationId) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("channel_id", channelId);
        fields.put("message_id", messageId);
        execute("delete_message", fields, operationId);
    }

    @Override
    public void addRole(String guildId, String userId, String roleId, String operationId) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("guild_id", guildId);
        fields.put("user_id", userId);
        fields.put("role_id", roleId);
        execute("add_role", fields, operationId);
    }

    @Override
    public void removeRole(String guildId, String userId, String roleId, String operationId) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("guild_id", guildId);
        fields.put("user_id", userId);
        fields.put("role_id", roleId);
        execute("remove_role", fields, operationId);
    }

    // ----- 4.1 Interaction 応答 (単一JVMのため直接REST) -----
    @Override
    public void reply(StarHandler.Interaction interaction, String content) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("flags", 64L); // 64 = EPHEMERAL (実行した本人にだけ表示)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 4L); // 4 = CHANNEL_MESSAGE_WITH_SOURCE (即時返信)
        body.put("data", data);
        request("POST", "/interactions/" + interaction.id + "/" + interaction.token + "/callback", body);
    }

    @Override
    public void ackComponent(StarHandler.Interaction interaction) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 6L); // 6 = DEFERRED_UPDATE_MESSAGE (元のメッセージは変更せず応答だけ済ませる)
        request("POST", "/interactions/" + interaction.id + "/" + interaction.token + "/callback", body);
    }

    @Override
    public void followup(StarHandler.Interaction interaction, String content) throws Exception {
        String appId = !interaction.applicationId.isBlank() ? interaction.applicationId : applicationId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("flags", 64L); // 64 = EPHEMERAL (実行した本人にだけ表示)
        request("POST", "/webhooks/" + appId + "/" + interaction.token, body);
    }

    @Override
    public void deferReply(StarHandler.Interaction interaction) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flags", 64L); // 64 = EPHEMERAL (実行した本人にだけ表示)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 5L); // 5 = DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE (応答を保留し「考え中」を表示)
        body.put("data", data);
        request("POST", "/interactions/" + interaction.id + "/" + interaction.token + "/callback", body);
    }

    @Override
    public void editOriginal(StarHandler.Interaction interaction, String content) throws Exception {
        String appId = !interaction.applicationId.isBlank() ? interaction.applicationId : applicationId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        // ephemeral かどうかは defer / reply の時点で決まるため、ここでは flags を付けない
        request("PATCH", "/webhooks/" + appId + "/" + interaction.token + "/messages/@original", body);
    }
}
