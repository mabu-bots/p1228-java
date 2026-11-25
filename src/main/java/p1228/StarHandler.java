package p1228;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// ===== 1. StarHandler (star-features.ts のイベント処理移植) =====
// 原本の GatewayClient (内部API) への依存を DiscordOps インターフェースに
// 置き換え、テストではフェイク実装で全分岐を検証できるようにする。
// 永続化は Store / StarFeatures を直接使う (単一JVM構成のため)。
public final class StarHandler {

    // ----- 1.1 Discord操作の境界 -----
    public interface DiscordOps {
        String sendMessage(String channelId, String content, Object components, String operationId) throws Exception;

        void deleteMessage(String channelId, String messageId, String operationId) throws Exception;

        void addRole(String guildId, String userId, String roleId, String operationId) throws Exception;

        void removeRole(String guildId, String userId, String roleId, String operationId) throws Exception;

        // VC自動生成 (Join to Create) 用。作成したチャンネルのIDを返す。
        String createVoiceChannel(String guildId, String name, String parentId, String operationId) throws Exception;

        void deleteChannel(String channelId, String operationId) throws Exception;

        void moveMember(String guildId, String userId, String channelId, String operationId) throws Exception;

        void reply(Interaction interaction, String content) throws Exception;

        void ackComponent(Interaction interaction) throws Exception;

        void followup(Interaction interaction, String content) throws Exception;

        void deferReply(Interaction interaction) throws Exception;

        void editOriginal(Interaction interaction, String content) throws Exception;
    }

    // AI会話の境界 (実装は AiClient。null の場合は /ai を無効として扱う)。
    public interface AiChat {
        String chat(String prompt) throws Exception;
    }

    // ----- 1.2 イベントとインタラクションの型 -----
    public static final class Event {
        public String eventId = "";
        public String eventType = "";
        public Map<String, Object> payload = new LinkedHashMap<>();

        public Event() {}

        public Event(String eventId, String eventType, Map<String, Object> payload) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.payload = payload;
        }
    }

    public static final class Interaction {
        public String id = "";
        public String applicationId = "";
        public String token = "";
        public int type = 0;
        public String guildId = "";
        public String channelId = "";
        public String userId = "";
        public String username = "";
        public String permissions = "0";
        public String commandName = "";
        public String subcommandName = "";
        public String customId = "";
        public String messageId = "";
        public Map<String, Object> options = new LinkedHashMap<>();

        public static Interaction fromPayload(Map<String, Object> payload) {
            Interaction it = new Interaction();
            it.id = Json.str(payload.get("id"));
            it.applicationId = Json.str(payload.get("application_id"));
            it.token = Json.str(payload.get("token"));
            it.type = Json.integer(payload.get("type"), 0);
            it.guildId = Json.str(payload.get("guild_id"));
            it.channelId = Json.str(payload.get("channel_id"));
            Map<String, Object> member = Json.obj(payload.get("member"));
            Map<String, Object> memberUser = Json.obj(member.get("user"));
            Map<String, Object> user = Json.obj(payload.get("user"));
            it.userId = !Json.str(memberUser.get("id")).isBlank() ? Json.str(memberUser.get("id")) : Json.str(user.get("id"));
            Map<String, Object> nameSource = !memberUser.isEmpty() ? memberUser : user;
            String globalName = Json.str(nameSource.get("global_name"));
            it.username = !globalName.isBlank() ? globalName : Json.str(nameSource.get("username"), it.userId);
            it.permissions = Json.str(member.get("permissions"), "0");
            Map<String, Object> data = Json.obj(payload.get("data"));
            it.commandName = Json.str(data.get("name"));
            it.customId = Json.str(data.get("custom_id"));
            Map<String, Object> message = Json.obj(payload.get("message"));
            it.messageId = Json.str(message.get("id"));
            List<Object> rawOptions = Json.arr(data.get("options"));
            List<Object> effective = rawOptions;
            for (Object o : rawOptions) {
                Map<String, Object> opt = Json.obj(o);
                if (Json.integer(opt.get("type"), 0) == 1) {
                    it.subcommandName = Json.str(opt.get("name"));
                    effective = Json.arr(opt.get("options"));
                    break;
                }
            }
            for (Object o : effective) {
                Map<String, Object> opt = Json.obj(o);
                if (opt.containsKey("value")) it.options.put(Json.str(opt.get("name")), opt.get("value"));
            }
            return it;
        }

        public String optionString(String name) {
            Object v = options.get(name);
            return v == null ? "" : String.valueOf(v);
        }

        public Long optionLong(String name) {
            Object v = options.get(name);
            return v instanceof Number n ? n.longValue() : null;
        }
    }

    // ----- 1.3 共有状態 (原本のモジュール内キャッシュ相当) -----
    private final Store store;
    private final StarFeatures features;
    private final DiscordOps ops;
    private final AiChat aiChat;
    private final Map<String, String> guildChannelParents = new HashMap<>();
    private final Map<String, String> guildNames = new HashMap<>();
    private final Map<String, Set<String>> memberRoles = new HashMap<>();
    private final Map<String, String> voiceChannels = new HashMap<>();
    public final List<String> logLines = new ArrayList<>();

    public StarHandler(Store store, StarFeatures features, DiscordOps ops, AiChat aiChat) {
        this.store = store;
        this.features = features;
        this.ops = ops;
        this.aiChat = aiChat;
    }

    private void log(String line) {
        logLines.add(line);
        System.out.println("[star] " + line);
    }

    // ===== 2. 静的ヘルパー (原本の export 関数) =====
    // Discord のメッセージ上限に合わせ、サロゲートペアを壊さずに
    // code point 単位で切り詰める。
    public static String truncateToCodePoints(String text, int maxCodePoints) {
        if (text == null) return "";
        int[] codePoints = text.codePoints().limit(maxCodePoints).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    public static String formatMemberMessage(String message, String userId, String username, String serverName) {
        String formatted = message
                .replace("{user}", "<@" + userId + ">")
                .replace("{user_id}", userId)
                .replace("{username}", username)
                .replace("{server}", serverName);
        return truncateToCodePoints(formatted, 2000);
    }

    // VCログの送信先解決。優先順位は チャンネル別 > カテゴリー別 > 共通。
    // 上書き値が "off" の場合は、その粒度でVCログ自体を無効化する
    // (より具体的な粒度の送信先設定があればそちらが優先される)。
    // 例: カテゴリーが "off" でも、チャンネル別の送信先があればそちらへ送る。
    // チャンネル別が "off" なら、カテゴリー・共通の設定に関わらず送らない。
    public static String resolveVoiceLogChannel(Map<String, Object> settings, String channelId, String categoryId) {
        String channelOverride = Json.str(settings.get("log.voice.channel." + channelId));
        if ("off".equals(channelOverride)) return "";
        if (!channelOverride.isBlank()) return channelOverride;
        if (categoryId != null && !categoryId.isBlank()) {
            String categoryOverride = Json.str(settings.get("log.voice.category." + categoryId));
            if ("off".equals(categoryOverride)) return "";
            if (!categoryOverride.isBlank()) return categoryOverride;
        }
        return Json.str(settings.get("log.voice"));
    }

    // 一時VCの名前生成。{username} / {user} を置換し、改行除去と
    // Discord のチャンネル名上限 (100文字) への切り詰めを行う。
    public static String buildTempChannelName(String template, String username) {
        String name = (template == null || template.isBlank()) ? "{username}のVC" : template;
        String user = username == null ? "" : username;
        name = name.replace("{username}", user).replace("{user}", user);
        name = name.replaceAll("[\\r\\n]+", " ").trim();
        if (name.isBlank()) name = "一時VC";
        return truncateToCodePoints(name, 100);
    }

    public static String eventGuildId(String eventType, Map<String, Object> payload) {
        if (payload.get("guild_id") instanceof String g) return g;
        if ((eventType.equals("GUILD_CREATE") || eventType.equals("GUILD_DELETE")) && payload.get("id") instanceof String id) return id;
        return "";
    }

    // ADMINISTRATOR(8) | MANAGE_GUILD(32) のどちらかがあれば管理者。
    public static boolean isGuildAdmin(Interaction interaction) {
        try {
            long permissions = Long.parseLong(interaction.permissions);
            return (permissions & 8L) != 0 || (permissions & 32L) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ===== 3. 応答用ヘルパー =====
    private void reply(Interaction interaction, String content) throws Exception {
        ops.reply(interaction, content);
    }

    private String sendMessage(String channelId, String content, String operationId, Object components) throws Exception {
        return ops.sendMessage(channelId, content, components, operationId);
    }

    private void sendLog(Map<String, Object> settings, String key, String content, String eventId) throws Exception {
        String channelId = Json.str(settings.get("log." + key));
        if (!channelId.isBlank()) sendMessage(channelId, content, eventId + ":log:" + key, null);
    }

    private StarFeatures.GameSettings gameSettings(Map<String, Object> settings) {
        return StarFeatures.GameSettings.fromSettingValue(settings.get("game"));
    }

    // ===== 4. コマンド処理 =====
    public void respondToCommand(Interaction interaction) throws Exception {
        String guildId = interaction.guildId;
        if (guildId == null || guildId.isBlank()) {
            reply(interaction, "このコマンドはサーバー内で使用してください。");
            return;
        }
        String commandName = interaction.commandName;
        boolean adminCommand = Commands.isAdminCommand(commandName, interaction.subcommandName);
        if (adminCommand && !isGuildAdmin(interaction)) {
            reply(interaction, "この操作には「サーバー管理」権限が必要です。");
            return;
        }
        Map<String, Object> settings = store.getGuildSettings(guildId);
        String userId = interaction.userId;
        String eventId = interaction.id;
        String message;

        switch (commandName) {
            case "ping" -> message = "Pong! 星燈は稼働中です。";
            case "welcome", "goodbye" -> {
                String key = commandName.equals("welcome") ? "member_join" : "member_leave";
                String label = commandName.equals("welcome") ? "参加" : "退出";
                if ("disable".equals(interaction.subcommandName)) {
                    store.setGuildSetting(guildId, key, null);
                    message = label + "メッセージを無効にしました。";
                } else {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("channel_id", interaction.optionString("channel"));
                    value.put("message", interaction.optionString("message"));
                    store.setGuildSetting(guildId, key, value);
                    message = label + "メッセージを設定しました。";
                }
            }
            case "autorole" -> {
                if ("disable".equals(interaction.subcommandName)) {
                    store.setGuildSetting(guildId, "autorole", null);
                    message = "自動ロールを無効にしました。";
                } else {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("role_id", interaction.optionString("role"));
                    store.setGuildSetting(guildId, "autorole", value);
                    message = "自動ロールを設定しました。";
                }
            }
            case "blacklist" -> {
                String targetId = interaction.optionString("user");
                if ("add".equals(interaction.subcommandName)) {
                    store.addBlacklist(guildId, targetId, interaction.optionString("reason"));
                    message = "ユーザーを自動ロールのブラックリストに追加しました。";
                } else {
                    store.removeBlacklist(guildId, targetId);
                    message = "ユーザーをブラックリストから削除しました。";
                }
            }
            case "logs" -> {
                String eventType = interaction.optionString("event");
                String targetId = interaction.optionString("target_id");
                String key = eventType.equals("voice_category") ? "voice.category." + targetId
                        : eventType.equals("voice_channel") ? "voice.channel." + targetId : eventType;
                if ((eventType.equals("voice_category") || eventType.equals("voice_channel")) && targetId.isBlank()) {
                    reply(interaction, "VCカテゴリー別・チャンネル別の設定には target_id が必要です。");
                    return;
                }
                if ("off".equals(interaction.subcommandName)) {
                    if (!eventType.equals("voice_category") && !eventType.equals("voice_channel")) {
                        reply(interaction, "無効化（off）はVCカテゴリー別・VCチャンネル別でのみ使えます。");
                        return;
                    }
                    store.setGuildSetting(guildId, "log." + key, "off");
                    message = "対象のVCログを無効にしました（共通ログへのフォールバックもしません）。再開するには set で送信先を設定するか、disable で設定自体を解除してください。";
                } else if ("disable".equals(interaction.subcommandName)) {
                    store.setGuildSetting(guildId, "log." + key, null);
                    message = "ログ送信先を解除しました。";
                } else {
                    store.setGuildSetting(guildId, "log." + key, interaction.optionString("channel"));
                    message = "ログ送信先を設定しました。VCはチャンネル別設定がカテゴリー別設定より優先されます。";
                }
            }
            case "vc-auto" -> {
                if ("off".equals(interaction.subcommandName)) {
                    store.setGuildSetting(guildId, "vc_auto", null);
                    message = "VC自動生成を無効にしました。";
                } else {
                    String lobbyId = interaction.optionString("lobby");
                    String categoryId = interaction.optionString("category");
                    if (lobbyId.isBlank() || categoryId.isBlank()) {
                        reply(interaction, "ロビーVCと生成先カテゴリーを指定してください。");
                        return;
                    }
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("lobby_channel_id", lobbyId);
                    value.put("category_id", categoryId);
                    String template = interaction.optionString("name_template");
                    if (!template.isBlank()) value.put("name_template", template);
                    store.setGuildSetting(guildId, "vc_auto", value);
                    message = "VC自動生成を設定しました。ロビーVCに参加したユーザーを、専用の一時VCへ自動で移動します（一時VCが空になると自動で削除します）。";
                }
            }
            case "sticky" -> {
                String channelId = interaction.optionString("channel");
                if ("remove".equals(interaction.subcommandName)) {
                    Store.StickyMessage current = store.getStickyMessage(guildId, channelId);
                    store.removeStickyMessage(guildId, channelId);
                    if (current != null && !current.messageId.isBlank()) {
                        ops.deleteMessage(channelId, current.messageId, eventId + ":sticky-remove");
                    }
                    message = "固定メッセージを解除しました。";
                } else {
                    Store.StickyMessage sticky = new Store.StickyMessage();
                    sticky.guildId = guildId;
                    sticky.channelId = channelId;
                    sticky.content = interaction.optionString("message");
                    Store.StickyMessage current = store.getStickyMessage(guildId, channelId);
                    sticky.messageId = current == null ? "" : current.messageId;
                    store.setStickyMessage(sticky);
                    String sentId = moveSticky(sticky, eventId);
                    store.updateStickyMessageId(guildId, channelId, "", sentId);
                    message = "固定メッセージを設定しました。メンバーが投稿するたびに最下部へ再投稿します。";
                }
            }
            case "role-panel" -> {
                String channelId = interaction.optionString("channel");
                List<Object> buttons = new ArrayList<>();
                List<Object> roleIds = new ArrayList<>();
                for (int i = 1; i <= 5; i++) {
                    String roleId = interaction.optionString("role" + i);
                    if (roleId.isBlank()) continue;
                    roleIds.add(roleId);
                    Map<String, Object> button = new LinkedHashMap<>();
                    button.put("type", 2L);
                    button.put("style", 2L);
                    String label = interaction.optionString("label" + i);
                    button.put("label", label.isBlank() ? "ロール " + i : label);
                    button.put("custom_id", "star-role:" + roleId);
                    buttons.add(button);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", 1L);
                row.put("components", buttons);
                String sentId = sendMessage(channelId, interaction.optionString("title"), eventId + ":role-panel", List.of(row));
                store.setGuildSetting(guildId, "role-panel:" + sentId, roleIds);
                message = "ロールパネルを投稿しました。";
            }
            case "game-config" -> {
                StarFeatures.GameSettings current = gameSettings(settings);
                Map<String, Object> updated = current.toSettingValue();
                for (String key : List.of("points_per_message", "cooldown_seconds", "daily_limit", "lottery_cost", "lottery_prize", "lottery_chance")) {
                    Long v = interaction.optionLong(key);
                    if (v != null) updated.put(key, v);
                }
                store.setGuildSetting(guildId, "game", updated);
                StarFeatures.GameSettings g = StarFeatures.GameSettings.fromSettingValue(updated);
                message = "ゲーム設定を更新しました。メッセージ獲得 " + g.pointsPerMessage() + "pt / クールダウン "
                        + g.cooldownSeconds() + "秒 / 日上限 " + g.dailyLimit() + "pt / 抽選 " + g.lotteryCost()
                        + "pt、当選確率 " + g.lotteryChance() + "%、追加報酬 " + g.lotteryPrize() + "pt";
            }
            case "omikuji" -> {
                String fortune;
                try {
                    fortune = features.drawOmikuji(guildId, userId, eventId);
                } catch (RuntimeException error) {
                    if (respondKnownGameError(interaction, error)) return;
                    throw error;
                }
                message = "今日のおみくじは **" + fortune + "** です。";
            }
            case "points" -> {
                Store.PointResult result = features.getPointAccount(guildId, userId);
                message = "所持ポイント: **" + result.balance() + " pt**";
            }
            case "lottery" -> {
                StarFeatures.GameSettings game = gameSettings(settings);
                StarFeatures.LotteryResult result;
                try {
                    result = features.drawLottery(guildId, userId, eventId, game.lotteryCost(), game.lotteryPrize(), game.lotteryChance());
                } catch (RuntimeException error) {
                    if (respondKnownGameError(interaction, error)) return;
                    throw error;
                }
                message = (result.won() ? "当選！追加で " + game.lotteryPrize() + " pt 獲得しました。" : "今回ははずれです。")
                        + " 残高: **" + result.points().balance() + " pt**";
            }
            case "shop" -> {
                if ("add".equals(interaction.subcommandName)) {
                    Store.ShopItem item = new Store.ShopItem();
                    item.itemId = interaction.optionString("item_id");
                    item.name = interaction.optionString("name");
                    Long price = interaction.optionLong("price");
                    item.price = price == null ? 0 : price;
                    item.roleId = interaction.optionString("role");
                    features.upsertShopItem(guildId, item);
                    message = "商品「" + item.name + "」を登録しました。";
                } else {
                    List<Store.ShopItem> items = features.listShopItems(guildId);
                    List<Store.ShopItem> shown = items.subList(0, Math.min(10, items.size()));
                    if (shown.isEmpty()) {
                        message = "販売中の商品はありません。";
                    } else {
                        List<String> lines = new ArrayList<>();
                        for (Store.ShopItem item : shown) lines.add("`" + item.itemId + "` — " + item.name + ": " + item.price + " pt");
                        message = String.join("\n", lines);
                        if (items.size() > shown.size()) message += "\nほか " + (items.size() - shown.size()) + " 件";
                    }
                }
            }
            case "buy" -> {
                String itemId = interaction.optionString("item_id");
                StarFeatures.BuyResult purchase;
                try {
                    purchase = features.buyShopItem(guildId, userId, itemId, eventId);
                } catch (RuntimeException error) {
                    if (respondKnownGameError(interaction, error)) return;
                    throw error;
                }
                if (!purchase.rolePending()) {
                    message = "「" + purchase.item().name + "」は購入済みで、ロール付与も完了しています。残高: **" + purchase.points().balance() + " pt**";
                } else {
                    try {
                        ops.addRole(guildId, userId, purchase.item().roleId, eventId + ":shop-role");
                        features.completeShopRoleGrant(guildId, userId, itemId);
                        message = "「" + purchase.item().name + "」のロールを付与しました。残高: **" + purchase.points().balance() + " pt**";
                    } catch (Exception error) {
                        log("shop_role_grant_failed guild=" + guildId + " user=" + userId + " item=" + itemId + " error=" + error.getMessage());
                        message = "購入は記録されていますが、ロール付与は未完了です。再度 `/buy item_id:" + itemId
                                + "` を実行すると、追加ポイントなしで再試行できます。残高: **" + purchase.points().balance() + " pt**";
                    }
                }
            }
            case "ai" -> {
                respondToAi(interaction);
                return;
            }
            default -> message = "このコマンドには対応していません。";
        }
        reply(interaction, message);
    }

    // ----- 4.1 AI会話 (/ai) -----
    // LLM の応答生成には時間がかかるため、先に defer で応答を保留し、
    // 生成が終わってから元の応答を編集して回答を届ける。生成に失敗した
    // 場合も編集で失敗メッセージを届け、理由は log に残す。
    private void respondToAi(Interaction interaction) throws Exception {
        String prompt = interaction.optionString("prompt").trim();
        if (prompt.isBlank()) {
            reply(interaction, "質問を入力してください。");
            return;
        }
        if (aiChat == null) {
            reply(interaction, "AI機能は現在有効化されていません。");
            return;
        }
        ops.deferReply(interaction);
        try {
            String answer = aiChat.chat(prompt);
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("AI returned an empty response");
            }
            ops.editOriginal(interaction, truncateToCodePoints(answer, 2000));
        } catch (Exception error) {
            log("ai_chat_failed user=" + interaction.userId + " error=" + error.getMessage());
            ops.editOriginal(interaction, "AIの応答取得に失敗しました。時間をおいて再度お試しください。");
        }
    }

    // ===== 5. エラー処理とコンポーネント処理 =====
    private static String expectedGameError(RuntimeException error) {
        String detail = String.valueOf(error.getMessage()).toLowerCase();
        if (detail.contains("insufficient points")) return "ポイントが足りません。";
        if (detail.contains("omikuji already drawn today")) return "おみくじは本日すでに引いています。";
        if (detail.contains("shop item already owned")) return "この商品はすでに購入済みです。";
        if (detail.contains("shop item not found")) return "商品が見つかりません。";
        return null;
    }

    private boolean respondKnownGameError(Interaction interaction, RuntimeException error) throws Exception {
        String message = expectedGameError(error);
        if (message == null) return false;
        reply(interaction, message);
        return true;
    }

    // ----- 5.1 コンポーネント処理 (ロールパネル) -----
    public void handleComponent(Interaction interaction) throws Exception {
        String guildId = interaction.guildId;
        String userId = interaction.userId;
        String messageId = interaction.messageId;
        String customId = interaction.customId;
        String roleId = null;
        if (customId != null && customId.startsWith("star-role:")) {
            String candidate = customId.substring("star-role:".length());
            if (!candidate.isBlank() && candidate.chars().allMatch(Character::isDigit)) roleId = candidate;
        }
        if (guildId.isBlank() || userId.isBlank() || messageId.isBlank() || roleId == null) {
            reply(interaction, "無効なロールパネルです。");
            return;
        }
        Map<String, Object> settings = store.getGuildSettings(guildId);
        Object allowed = settings.get("role-panel:" + messageId);
        List<Object> allowedRoles = Json.arr(allowed);
        String targetRoleId = roleId;
        boolean registered = allowedRoles.stream().anyMatch(r -> targetRoleId.equals(String.valueOf(r)));
        if (!registered) {
            reply(interaction, "このロールはパネルに登録されていません。");
            return;
        }
        if (store.isBlacklisted(guildId, userId)) {
            log("role_panel_denied_by_blacklist guild=" + guildId + " user=" + userId);
            sendLog(settings, "autorole_refused", "ブラックリスト対象者のロールパネル操作を拒否しました (`" + userId + "`)", interaction.id);
            reply(interaction, "このサーバーではロールを付与できません。");
            return;
        }
        ops.ackComponent(interaction);
        try {
            ops.addRole(guildId, userId, roleId, interaction.id);
            ops.followup(interaction, "ロールを付与しました。");
        } catch (Exception error) {
            log("role_panel_grant_failed guild=" + guildId + " user=" + userId + " role=" + roleId + " error=" + error.getMessage());
            ops.followup(interaction, "ロールを付与できませんでした。管理者にお問い合わせください。");
        }
    }

    // ----- 5.2 スティッキーの移動 -----
    // 保存済みの message_id は、まず空文字へ条件付きで差し替えてから旧メッセージを
    // 削除し、新規投稿する。呼び出し側は戻り値の新IDを、expectedOld="" の
    // 条件付き更新で保存する (一連の流れで「現在のIDが期待どおりのときだけ
    // 差し替える」ことで、競合する再投稿同士の上書きを防ぐ)。
    public String moveSticky(Store.StickyMessage sticky, String eventId) throws Exception {
        String oldMessageId = sticky.messageId;
        if (oldMessageId != null && !oldMessageId.isBlank()) {
            store.updateStickyMessageId(sticky.guildId, sticky.channelId, oldMessageId, "");
            try {
                ops.deleteMessage(sticky.channelId, oldMessageId, eventId + ":sticky-delete");
            } catch (Exception error) {
                log("sticky_previous_message_delete_failed guild=" + sticky.guildId + " channel=" + sticky.channelId + " error=" + error.getMessage());
            }
        }
        return sendMessage(sticky.channelId, sticky.content, eventId + ":sticky-post", null);
    }

    // ===== 6. メンバーと音声のイベント =====
    private void handleGuildMember(Event event, String guildId, Map<String, Object> payload) throws Exception {
        Map<String, Object> user = Json.obj(payload.get("user"));
        String userId = Json.str(user.get("id"));
        if (userId.isBlank() || Json.bool(user.get("bot"))) return;
        Map<String, Object> settings = store.getGuildSettings(guildId);
        String globalName = Json.str(user.get("global_name"));
        String nick = Json.str(payload.get("nick"));
        String username = !globalName.isBlank() ? globalName : !nick.isBlank() ? nick : Json.str(user.get("username"), userId);
        String memberKey = guildId + ":" + userId;

        if (event.eventType.equals("GUILD_MEMBER_ADD")) {
            if (store.isBlacklisted(guildId, userId)) {
                log("automatic_role_denied_by_blacklist guild=" + guildId + " user=" + userId);
                sendLog(settings, "autorole_refused", "自動ロール付与を拒否しました: " + username + " (`" + userId + "`)", event.eventId);
            } else {
                Map<String, Object> autoRole = Json.obj(settings.get("autorole"));
                String roleId = Json.str(autoRole.get("role_id"));
                if (!roleId.isBlank()) {
                    try {
                        ops.addRole(guildId, userId, roleId, event.eventId + ":autorole");
                    } catch (Exception error) {
                        log("automatic_role_grant_failed guild=" + guildId + " user=" + userId + " error=" + error.getMessage());
                        sendLog(settings, "autorole_refused", "自動ロールを付与できませんでした: " + username + " (`" + userId + "`)", event.eventId);
                    }
                }
            }
            Map<String, Object> welcome = Json.obj(settings.get("member_join"));
            String welcomeChannel = Json.str(welcome.get("channel_id"));
            String welcomeMessage = Json.str(welcome.get("message"));
            if (!welcomeChannel.isBlank() && !welcomeMessage.isBlank()) {
                sendMessage(welcomeChannel,
                        formatMemberMessage(welcomeMessage, userId, username, guildNames.getOrDefault(guildId, guildId)),
                        event.eventId + ":welcome", null);
            }
            memberRoles.put(memberKey, roleSet(payload));
            sendLog(settings, "member_join", "参加: " + username + " (`" + userId + "`)", event.eventId);
        } else if (event.eventType.equals("GUILD_MEMBER_REMOVE")) {
            Map<String, Object> goodbye = Json.obj(settings.get("member_leave"));
            String goodbyeChannel = Json.str(goodbye.get("channel_id"));
            String goodbyeMessage = Json.str(goodbye.get("message"));
            if (!goodbyeChannel.isBlank() && !goodbyeMessage.isBlank()) {
                sendMessage(goodbyeChannel,
                        formatMemberMessage(goodbyeMessage, userId, username, guildNames.getOrDefault(guildId, guildId)),
                        event.eventId + ":goodbye", null);
            }
            memberRoles.remove(memberKey);
            sendLog(settings, "member_leave", "退出: " + username + " (`" + userId + "`)", event.eventId);
        } else { // GUILD_MEMBER_UPDATE
            Set<String> nextRoles = roleSet(payload);
            Set<String> previousRoles = memberRoles.get(memberKey);
            memberRoles.put(memberKey, nextRoles);
            if (previousRoles != null) {
                List<String> added = new ArrayList<>();
                for (String r : nextRoles) if (!previousRoles.contains(r)) added.add(r);
                List<String> removed = new ArrayList<>();
                for (String r : previousRoles) if (!nextRoles.contains(r)) removed.add(r);
                if (!added.isEmpty() || !removed.isEmpty()) {
                    sendLog(settings, "role_update", "ロール変更: " + username + " (`" + userId + "`)\n追加: "
                            + (added.isEmpty() ? "なし" : String.join(", ", added))
                            + "\n削除: " + (removed.isEmpty() ? "なし" : String.join(", ", removed)), event.eventId);
                }
            }
        }
    }

    private static Set<String> roleSet(Map<String, Object> payload) {
        Set<String> roles = new HashSet<>();
        for (Object o : Json.arr(payload.get("roles"))) roles.add(String.valueOf(o));
        return roles;
    }

    private void handleVoiceState(Event event, Map<String, Object> payload) throws Exception {
        String guildId = Json.str(payload.get("guild_id"));
        String userId = Json.str(payload.get("user_id"));
        if (guildId.isBlank() || userId.isBlank()) return;
        String key = guildId + ":" + userId;
        String oldChannel = voiceChannels.getOrDefault(key, "");
        Object channelValue = payload.get("channel_id");
        String newChannel = channelValue == null ? "" : String.valueOf(channelValue);
        if (!newChannel.isBlank()) voiceChannels.put(key, newChannel);
        else voiceChannels.remove(key);
        if (oldChannel.equals(newChannel)) return;
        Map<String, Object> settings = store.getGuildSettings(guildId);
        String targetChannel = !newChannel.isBlank() ? newChannel : oldChannel;
        String categoryId = guildChannelParents.getOrDefault(guildId + ":" + targetChannel, "");
        String logChannel = resolveVoiceLogChannel(settings, targetChannel, categoryId);
        if (!logChannel.isBlank()) {
            String detail;
            if (oldChannel.isBlank() && !newChannel.isBlank()) detail = "VC参加: <@" + userId + "> → <#" + newChannel + ">";
            else if (!oldChannel.isBlank() && newChannel.isBlank()) detail = "VC退出: <@" + userId + "> ← <#" + oldChannel + ">";
            else detail = "VC移動: <@" + userId + "> <#" + oldChannel + "> → <#" + newChannel + ">";
            sendMessage(logChannel, detail, event.eventId + ":voice-log", null);
        }
        handleVcAuto(event, payload, settings, guildId, userId, oldChannel, newChannel);
    }

    // ===== 6.1 VC自動生成 (Join to Create) =====
    // ロビーVCへの参加を検知して専用の一時VCを作り、本人のみを移動させる。
    // 一時VCは全員が退出した時点で削除する。権限不足などで Discord 操作が
    // 失敗しても Bot 本体は落とさず、理由をログに残す。
    private void handleVcAuto(Event event, Map<String, Object> payload, Map<String, Object> settings,
                              String guildId, String userId, String oldChannel, String newChannel) {
        Map<String, Object> vcAuto = Json.obj(settings.get("vc_auto"));
        String lobbyId = Json.str(vcAuto.get("lobby_channel_id"));
        if (!lobbyId.isBlank() && lobbyId.equals(newChannel) && !lobbyId.equals(oldChannel)) {
            Map<String, Object> member = Json.obj(payload.get("member"));
            Map<String, Object> user = Json.obj(member.get("user"));
            if (!Json.bool(user.get("bot"))) {
                String username = voiceDisplayName(member, user, userId);
                String name = buildTempChannelName(Json.str(vcAuto.get("name_template")), username);
                try {
                    String createdId = ops.createVoiceChannel(guildId, name, Json.str(vcAuto.get("category_id")),
                            event.eventId + ":vc-auto-create");
                    Store.TempVoiceChannel temp = new Store.TempVoiceChannel();
                    temp.guildId = guildId;
                    temp.channelId = createdId;
                    temp.ownerId = userId;
                    temp.name = name;
                    store.putTempVoiceChannel(temp);
                    log("vc_auto_created guild=" + guildId + " channel=" + createdId + " owner=" + userId);
                    try {
                        ops.moveMember(guildId, userId, createdId, event.eventId + ":vc-auto-move");
                    } catch (Exception moveError) {
                        // 移動できない一時VCは孤児になるため、その場で削除する。
                        log("vc_auto_move_failed guild=" + guildId + " user=" + userId + " channel=" + createdId
                                + " error=" + moveError.getMessage());
                        try {
                            ops.deleteChannel(createdId, event.eventId + ":vc-auto-cleanup");
                            store.removeTempVoiceChannel(guildId, createdId);
                        } catch (Exception cleanupError) {
                            log("vc_auto_cleanup_failed guild=" + guildId + " channel=" + createdId
                                    + " error=" + cleanupError.getMessage());
                        }
                    }
                } catch (Exception createError) {
                    log("vc_auto_create_failed guild=" + guildId + " user=" + userId + " error=" + createError.getMessage());
                }
            }
        }
        // 退出・移動で空になった一時VCは削除する (所有者かどうかは問わない)。
        if (!oldChannel.isBlank() && !oldChannel.equals(newChannel)) {
            cleanupTempChannelIfEmpty(guildId, oldChannel, event.eventId);
        }
    }

    private static String voiceDisplayName(Map<String, Object> member, Map<String, Object> user, String userId) {
        String globalName = Json.str(user.get("global_name"));
        if (!globalName.isBlank()) return globalName;
        String nick = Json.str(member.get("nick"));
        if (!nick.isBlank()) return nick;
        return Json.str(user.get("username"), userId);
    }

    // 一時VCが空なら削除して記録も消す。削除が 404 (既に存在しない) の場合も
    // 記録だけは消し、次回以降に残らないようにする。
    private void cleanupTempChannelIfEmpty(String guildId, String channelId, String eventId) {
        Store.TempVoiceChannel temp = store.getTempVoiceChannel(guildId, channelId);
        if (temp == null) return;
        String prefix = guildId + ":";
        boolean occupied = voiceChannels.entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith(prefix) && channelId.equals(e.getValue()));
        if (occupied) return;
        try {
            ops.deleteChannel(channelId, eventId + ":vc-auto-delete");
            store.removeTempVoiceChannel(guildId, channelId);
            log("vc_auto_deleted guild=" + guildId + " channel=" + channelId);
        } catch (Exception e) {
            log("vc_auto_delete_failed guild=" + guildId + " channel=" + channelId + " error=" + e.getMessage());
            if (String.valueOf(e.getMessage()).contains("404")) {
                store.removeTempVoiceChannel(guildId, channelId);
            }
        }
    }

    // ===== 7. イベント振り分け =====
    public void handleEvent(Event event) throws Exception {
        if (event.eventType.equals("INTERACTION_CREATE")) {
            Interaction interaction = Interaction.fromPayload(event.payload);
            if (interaction.type == 3) {
                handleComponent(interaction);
                return;
            }
            if (interaction.type == 2) {
                try {
                    respondToCommand(interaction);
                    ExecLog.log(interaction.commandName, interaction.guildId, interaction.channelId,
                            interaction.userId, null);
                } catch (Exception e) {
                    ExecLog.log(interaction.commandName, interaction.guildId, interaction.channelId,
                            interaction.userId, e.getMessage());
                    throw e;
                }
                return;
            }
            return;
        }
        Map<String, Object> payload = event.payload;
        String guildId = eventGuildId(event.eventType, payload);
        switch (event.eventType) {
            case "GUILD_CREATE" -> {
                if (payload.get("name") instanceof String name) guildNames.put(guildId, name);
                for (Object o : Json.arr(payload.get("channels"))) {
                    Map<String, Object> channel = Json.obj(o);
                    long type = Json.lng(channel.get("type"), -1);
                    if ((type == 2 || type == 13) && payloadParent(channel) != null) {
                        guildChannelParents.put(guildId + ":" + Json.str(channel.get("id")), Json.str(channel.get("parent_id")));
                    }
                }
                for (Object o : Json.arr(payload.get("members"))) {
                    Map<String, Object> member = Json.obj(o);
                    Map<String, Object> user = Json.obj(member.get("user"));
                    if (!Json.str(user.get("id")).isBlank()) {
                        memberRoles.put(guildId + ":" + Json.str(user.get("id")), roleSet(member));
                    }
                }
                for (Object o : Json.arr(payload.get("voice_states"))) {
                    Map<String, Object> state = Json.obj(o);
                    if (!Json.str(state.get("user_id")).isBlank() && !Json.str(state.get("channel_id")).isBlank()) {
                        voiceChannels.put(guildId + ":" + Json.str(state.get("user_id")), Json.str(state.get("channel_id")));
                    }
                }
                // 前回終了時に残った一時VCの掃除 (在室者ゼロのものだけ削除)。
                for (Store.TempVoiceChannel temp : store.listTempVoiceChannels(guildId)) {
                    cleanupTempChannelIfEmpty(guildId, temp.channelId, event.eventId);
                }
            }
            case "GUILD_DELETE" -> {
                String prefix = guildId + ":";
                guildChannelParents.keySet().removeIf(k -> k.startsWith(prefix));
                memberRoles.keySet().removeIf(k -> k.startsWith(prefix));
                voiceChannels.keySet().removeIf(k -> k.startsWith(prefix));
                guildNames.remove(guildId);
            }
            case "GUILD_MEMBER_ADD", "GUILD_MEMBER_REMOVE", "GUILD_MEMBER_UPDATE" -> handleGuildMember(event, guildId, payload);
            case "VOICE_STATE_UPDATE" -> handleVoiceState(event, payload);
            case "MESSAGE_CREATE" -> {
                String messageId = Json.str(payload.get("id"));
                String channelId = Json.str(payload.get("channel_id"));
                Map<String, Object> author = Json.obj(payload.get("author"));
                String authorId = Json.str(author.get("id"));
                if (guildId.isBlank() || authorId.isBlank() || Json.bool(author.get("bot"))) return;
                Map<String, Object> settings = store.getGuildSettings(guildId);
                StarFeatures.GameSettings game = gameSettings(settings);
                features.earnPoints(guildId, authorId, event.eventId, game.pointsPerMessage(), game.cooldownSeconds(), game.dailyLimit());
                Store.StickyMessage sticky = store.getStickyMessage(guildId, channelId);
                if (sticky != null && !sticky.messageId.equals(messageId)) {
                    String sentId = moveSticky(sticky, event.eventId);
                    store.updateStickyMessageId(guildId, channelId, "", sentId);
                }
            }
            case "MESSAGE_UPDATE" -> {
                if (!guildId.isBlank()) {
                    sendLog(store.getGuildSettings(guildId), "message_edit",
                            "メッセージ編集: <#" + Json.str(payload.get("channel_id")) + "> / `" + Json.str(payload.get("id")) + "`", event.eventId);
                }
            }
            case "MESSAGE_DELETE" -> {
                if (guildId.isBlank()) return;
                Map<String, Object> settings = store.getGuildSettings(guildId);
                String channelId = Json.str(payload.get("channel_id"));
                String messageId = Json.str(payload.get("id"));
                sendLog(settings, "message_delete", "メッセージ削除: <#" + channelId + "> / `" + messageId + "`", event.eventId);
                Store.StickyMessage sticky = store.getStickyMessage(guildId, channelId);
                if (sticky != null && sticky.messageId.equals(messageId)) {
                    String sentId = moveSticky(sticky, event.eventId);
                    store.updateStickyMessageId(guildId, channelId, "", sentId);
                }
            }
            case "MESSAGE_DELETE_BULK" -> {
                String channelId = Json.str(payload.get("channel_id"));
                List<String> ids = new ArrayList<>();
                for (Object o : Json.arr(payload.get("ids"))) ids.add(String.valueOf(o));
                if (guildId.isBlank() || channelId.isBlank()) return;
                Map<String, Object> settings = store.getGuildSettings(guildId);
                sendLog(settings, "message_delete", ids.size() + "件のメッセージを一括削除: <#" + channelId + ">", event.eventId);
                Store.StickyMessage sticky = store.getStickyMessage(guildId, channelId);
                if (sticky != null && ids.contains(sticky.messageId)) {
                    String sentId = moveSticky(sticky, event.eventId);
                    store.updateStickyMessageId(guildId, channelId, "", sentId);
                }
            }
            default -> {
                if (event.eventType.startsWith("CHANNEL_")) {
                    String channelId = Json.str(payload.get("id"));
                    long type = Json.lng(payload.get("type"), -1);
                    if ((type == 2 || type == 13) && payload.get("parent_id") instanceof String parent) {
                        guildChannelParents.put(guildId + ":" + channelId, parent);
                    } else {
                        guildChannelParents.remove(guildId + ":" + channelId);
                    }
                }
            }
        }
    }

    private static Object payloadParent(Map<String, Object> channel) {
        Object parent = channel.get("parent_id");
        return parent instanceof String s && !s.isBlank() ? s : null;
    }
}
