package p1228;

import java.time.Instant;

// ===== ExecLog (コマンド実行ログ) =====
// スラッシュコマンドの実行を標準出力へ1行で記録する:
//   [exec] <ISO時刻> cmd=<コマンド名> route=slash guild=<ID> channel=<ID> user=<ID> result=<ok|error>
// 失敗時は末尾に error="<要点>" を付ける。
// 記録するのはコマンド名・ID類・成否のみで、ユーザーの入力テキスト
// (オプション値、特に /ai のプロンプト内容) は絶対に記録しない。
public final class ExecLog {

    private ExecLog() {}

    private static String idOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    // 失敗の要点を1行に収める: 改行・引用符を潰し、長すぎる場合は切り詰める。
    static String summarizeError(String message) {
        if (message == null || message.isBlank()) return "unknown error";
        String cleaned = message.replaceAll("\\s+", " ").replace("\"", "'").trim();
        return StarHandler.truncateToCodePoints(cleaned, 120);
    }

    public static String format(Instant now, String commandName, String guildId, String channelId,
                                String userId, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("[exec] ").append(now);
        sb.append(" cmd=").append(idOrDash(commandName));
        sb.append(" route=slash");
        sb.append(" guild=").append(idOrDash(guildId));
        sb.append(" channel=").append(idOrDash(channelId));
        sb.append(" user=").append(idOrDash(userId));
        if (error == null) {
            sb.append(" result=ok");
        } else {
            sb.append(" result=error error=\"").append(summarizeError(error)).append("\"");
        }
        return sb.toString();
    }

    public static void log(String commandName, String guildId, String channelId, String userId, String error) {
        System.out.println(format(Instant.now(), commandName, guildId, channelId, userId, error));
    }
}
