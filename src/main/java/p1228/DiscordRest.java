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
    private Map<String, Object> request(String method, String path, Object body) throws Exception {
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
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
        if (operationId != null && !operationId.isBlank()) operationCache.put(operationId, result);
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
        data.put("flags", 64L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 4L);
        body.put("data", data);
        request("POST", "/interactions/" + interaction.id + "/" + interaction.token + "/callback", body);
    }

    @Override
    public void ackComponent(StarHandler.Interaction interaction) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 6L);
        request("POST", "/interactions/" + interaction.id + "/" + interaction.token + "/callback", body);
    }

    @Override
    public void followup(StarHandler.Interaction interaction, String content) throws Exception {
        String appId = !interaction.applicationId.isBlank() ? interaction.applicationId : applicationId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("flags", 64L);
        request("POST", "/webhooks/" + appId + "/" + interaction.token, body);
    }
}
