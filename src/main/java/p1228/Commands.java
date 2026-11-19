package p1228;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ===== 1. Commands (Slashコマンド定義) =====
// workers/src/general/star-features.ts の starCommands と
// workers/src/sync-commands.ts の振り分けをそのまま移植する。
// general = ping + starCommands + ai (ai は Java版独自のローカルLLM連携) /
// read-aloud 2体 = join, leave, say。
// オプション型: 1=SUB_COMMAND, 3=STRING, 4=INTEGER, 6=USER, 7=CHANNEL, 8=ROLE
public final class Commands {

    private Commands() {}

    // ----- 1.1 定義用ヘルパー -----
    private static Map<String, Object> option(String name, String description, int type, boolean required, Map<String, Object> extra) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", name);
        o.put("description", description);
        o.put("type", (long) type);
        o.put("required", required);
        if (extra != null) o.putAll(extra);
        return o;
    }

    private static Map<String, Object> option(String name, String description, int type) {
        return option(name, description, type, false, null);
    }

    private static Map<String, Object> option(String name, String description, int type, boolean required) {
        return option(name, description, type, required, null);
    }

    private static Map<String, Object> subcommand(String name, String description, List<Map<String, Object>> options) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", 1L);
        s.put("name", name);
        s.put("description", description);
        s.put("options", new ArrayList<>(options));
        return s;
    }

    private static Map<String, Object> command(String name, String description, List<Map<String, Object>> options) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("description", description);
        c.put("type", 1L);
        if (options != null) c.put("options", new ArrayList<>(options));
        return c;
    }

    private static Map<String, Object> extra(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static List<Object> choices(Object... nameValues) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", String.valueOf(nameValues[i]));
            c.put("value", String.valueOf(nameValues[i + 1]));
            list.add(c);
        }
        return list;
    }

    private static List<Object> logEventChoices() {
        return choices(
                "メッセージ編集", "message_edit",
                "メッセージ削除", "message_delete",
                "メンバー参加", "member_join",
                "メンバー退出", "member_leave",
                "ロール変更", "role_update",
                "自動ロール拒否", "autorole_refused",
                "VCログ（共通）", "voice",
                "VCカテゴリー別", "voice_category",
                "VCチャンネル別", "voice_channel");
    }

    // ===== 2. 星燈 (general) のコマンド =====
    public static List<Map<String, Object>> starCommands() {
        List<Map<String, Object>> commands = new ArrayList<>();
        commands.add(command("welcome", "参加時メッセージを設定します", List.of(
                subcommand("set", "参加時メッセージを設定", List.of(
                        option("channel", "送信先", 7, true, extra("channel_types", List.of(0L))),
                        option("message", "本文 ({user}, {username}, {server} が使用可能)", 3, true, extra("max_length", 1000L)))),
                subcommand("disable", "参加時メッセージを無効化", List.of()))));
        commands.add(command("goodbye", "退出時メッセージを設定します", List.of(
                subcommand("set", "退出時メッセージを設定", List.of(
                        option("channel", "送信先", 7, true, extra("channel_types", List.of(0L))),
                        option("message", "本文 ({user}, {username}, {server} が使用可能)", 3, true, extra("max_length", 1000L)))),
                subcommand("disable", "退出時メッセージを無効化", List.of()))));
        commands.add(command("autorole", "参加時の自動ロールを設定します", List.of(
                subcommand("set", "自動付与するロールを設定", List.of(
                        option("role", "付与するロール", 8, true))),
                subcommand("disable", "自動ロールを無効化", List.of()))));
        commands.add(command("blacklist", "自動ロール付与のブラックリストを管理します", List.of(
                subcommand("add", "ユーザーをブラックリストに追加", List.of(
                        option("user", "対象ユーザー", 6, true),
                        option("reason", "理由", 3, false, extra("max_length", 500L)))),
                subcommand("remove", "ユーザーをブラックリストから削除", List.of(
                        option("user", "対象ユーザー", 6, true))))));
        commands.add(command("logs", "イベントログの送信先を設定します", List.of(
                subcommand("set", "ログ送信先を設定", List.of(
                        option("event", "ログ種別", 3, true, extra("choices", logEventChoices())),
                        option("channel", "ログ送信先チャンネル", 7, true, extra("channel_types", List.of(0L))),
                        option("target_id", "VCのカテゴリーIDまたはチャンネルID（VC種別の場合）", 3))),
                subcommand("disable", "ログ送信先を解除", List.of(
                        option("event", "ログ種別", 3, true, extra("choices", logEventChoices())),
                        option("target_id", "VCのカテゴリーIDまたはチャンネルID（VC種別の場合）", 3))))));
        commands.add(command("sticky", "チャンネル下部に固定メッセージを表示します", List.of(
                subcommand("set", "固定メッセージを設定", List.of(
                        option("channel", "対象チャンネル", 7, true, extra("channel_types", List.of(0L))),
                        option("message", "固定する本文", 3, true, extra("max_length", 2000L)))),
                subcommand("remove", "固定メッセージを解除", List.of(
                        option("channel", "対象チャンネル", 7, true, extra("channel_types", List.of(0L))))))));
        List<Map<String, Object>> rolePanelOptions = new ArrayList<>();
        rolePanelOptions.add(option("channel", "投稿先", 7, true, extra("channel_types", List.of(0L))));
        rolePanelOptions.add(option("title", "パネルの説明", 3, true, extra("max_length", 500L)));
        for (int i = 1; i <= 5; i++) {
            rolePanelOptions.add(option("role" + i, "ロール" + i, 8, i == 1));
            rolePanelOptions.add(option("label" + i, "ボタン" + i + "の表示名", 3, false, extra("max_length", 80L)));
        }
        commands.add(command("role-panel", "ロール付与パネルを投稿します", List.of(
                subcommand("create", "最大5個のロールボタンを作成", rolePanelOptions))));
        commands.add(command("game-config", "ポイント獲得と抽選のルールを設定します", List.of(
                option("points_per_message", "メッセージ1回の獲得ポイント", 4, false, extra("min_value", 1L, "max_value", 1000L)),
                option("cooldown_seconds", "獲得クールダウン（秒）", 4, false, extra("min_value", 0L, "max_value", 86400L)),
                option("daily_limit", "1日の獲得上限", 4, false, extra("min_value", 1L, "max_value", 100000L)),
                option("lottery_cost", "抽選に必要なポイント", 4, false, extra("min_value", 1L, "max_value", 100000L)),
                option("lottery_prize", "当選時の追加ポイント", 4, false, extra("min_value", 0L, "max_value", 1000000L)),
                option("lottery_chance", "当選確率（%）", 4, false, extra("min_value", 0L, "max_value", 100L)))));
        commands.add(command("omikuji", "1日1回おみくじを引きます", null));
        commands.add(command("points", "ポイント残高を確認します", null));
        commands.add(command("lottery", "ポイントを使って抽選します", null));
        commands.add(command("shop", "ポイントショップを表示・管理します", List.of(
                subcommand("list", "販売中の商品を表示", List.of()),
                subcommand("add", "ロール商品を登録", List.of(
                        option("item_id", "商品ID（英数字・ハイフン）", 3, true, extra("max_length", 50L)),
                        option("name", "商品名", 3, true, extra("max_length", 100L)),
                        option("price", "価格", 4, true, extra("min_value", 1L, "max_value", 1000000L)),
                        option("role", "購入時に付与するロール", 8, true))))));
        commands.add(command("buy", "ポイントショップの商品を購入します", List.of(
                option("item_id", "商品ID", 3, true, extra("max_length", 50L)))));
        return commands;
    }

    public static List<Map<String, Object>> generalCommands() {
        List<Map<String, Object>> commands = new ArrayList<>();
        commands.add(command("ping", "星燈の応答状態を確認します", null));
        commands.addAll(starCommands());
        commands.add(command("ai", "ローカルAIに質問します", List.of(
                option("prompt", "質問・お題", 3, true, extra("max_length", 500L)))));
        return commands;
    }

    // ===== 3. 読み上げ (read_aloud_a / read_aloud_b) のコマンド =====
    public static List<Map<String, Object>> readAloudCommands() {
        List<Map<String, Object>> commands = new ArrayList<>();
        commands.add(command("join", "指定したボイスチャンネルに接続します", List.of(
                option("channel", "接続先チャンネル", 7, true, extra("channel_types", List.of(2L, 13L))))));
        commands.add(command("leave", "ボイスチャンネルから退出します", null));
        commands.add(command("say", "指定した文章を読み上げます", List.of(
                option("text", "読み上げる文章", 3, true, extra("max_length", 1000L)))));
        return commands;
    }

    public static List<Map<String, Object>> forBot(String botId) {
        return "general".equals(botId) ? generalCommands() : readAloudCommands();
    }

    public static String toJson(String botId) {
        return Json.stringify(forBot(botId));
    }

    // ===== 4. 管理者判定のモデル (star-features.ts と同一規則) =====
    // omikuji / points / lottery / buy / ai と shop list だけが非管理者コマンド。
    public static boolean isAdminCommand(String commandName, String subcommandName) {
        if (List.of("omikuji", "points", "lottery", "buy", "ai").contains(commandName)) return false;
        if ("shop".equals(commandName) && "list".equals(subcommandName)) return false;
        return true;
    }
}
