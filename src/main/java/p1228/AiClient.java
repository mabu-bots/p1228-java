package p1228;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ===== 1. AiClient (ローカルLLM クライアント) =====
// llama.cpp の llama-server が公開する OpenAI 互換 API
// (POST /v1/chat/completions) を叩く。ReadAloud.VoiceVoxClient と
// 同じく ProxySupport の HttpClient を使い、localhost 宛は
// プロキシを経由しない。応答生成は最大で数十秒かかるため、
// リクエストタイムアウトは長め (120秒) に取る。
public final class AiClient implements StarHandler.AiChat {

    // ----- 1.1 定数と状態 -----
    public static final String SYSTEM_PROMPT =
            "あなたはDiscordボット『星燈』に組み込まれたAIアシスタントです。日本語で簡潔に答えてください。";

    private final String baseUrl;
    private final HttpClient client;

    public AiClient(String baseUrl) {
        String normalized = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:8080" : baseUrl;
        this.baseUrl = normalized.replaceAll("/+$", "");
        this.client = ProxySupport.newClient(Duration.ofSeconds(5));
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ----- 1.2 応答生成 -----
    @Override
    public String chat(String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("AI prompt is empty");
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", prompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(system, user));
        body.put("max_tokens", 400L);
        body.put("temperature", 0.7);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AI chat request failed: HTTP " + response.statusCode());
        }
        Map<String, Object> root = Json.obj(Json.parse(response.body()));
        List<Object> choices = Json.arr(root.get("choices"));
        Map<String, Object> message = choices.isEmpty()
                ? new LinkedHashMap<>() : Json.obj(Json.obj(choices.get(0)).get("message"));
        String content = Json.str(message.get("content"));
        if (content.isBlank()) throw new IllegalStateException("AI returned an empty response");
        return content;
    }
}
