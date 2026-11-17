package p1228;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// ===== 1. P1228Test (オフライン単体テスト) =====
// ネットワークを使わず、JSON / Config / Store / StarFeatures /
// StarHandler / ReadAloud / Commands を検証する。
public final class P1228Test {

    // ----- 1.1 テスト基盤 -----
    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            failures.add(name);
            System.out.println("FAIL: " + name);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void checkThrows(String name, String expectedMessagePart, ThrowingRunnable action) {
        try {
            action.run();
            failed++;
            failures.add(name + " (例外が出なかった)");
            System.out.println("FAIL: " + name + " (例外が出なかった)");
        } catch (Exception e) {
            boolean ok = expectedMessagePart == null || String.valueOf(e.getMessage()).contains(expectedMessagePart);
            if (ok) {
                passed++;
                System.out.println("PASS: " + name);
            } else {
                failed++;
                failures.add(name + " (メッセージ不一致: " + e.getMessage() + ")");
                System.out.println("FAIL: " + name + " (メッセージ不一致: " + e.getMessage() + ")");
            }
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration d) {
            instant = instant.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    static final class FixedRandom extends Random {
        private final int value;

        FixedRandom(int value) {
            this.value = value;
        }

        @Override public int nextInt(int bound) {
            return Math.min(value, bound - 1);
        }
    }

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("p1228-test");
    }

    private static Store.ShopItem shopItem(String id, String name, long price, String roleId) {
        Store.ShopItem item = new Store.ShopItem();
        item.itemId = id;
        item.name = name;
        item.price = price;
        item.roleId = roleId;
        return item;
    }

    private static void grantPoints(StarFeatures features, MutableClock clock, String guild, String user, int times) {
        for (int i = 0; i < times; i++) {
            features.earnPoints(guild, user, "grant-" + user + "-" + i + "-" + System.nanoTime(), 10, 0, 100000);
            clock.advance(Duration.ofSeconds(1));
        }
    }

    public static void main(String[] args) throws Exception {
        testJson();
        testConfig();
        testFormatAndVoiceRouting();
        testEarn();
        testLottery();
        testOmikuji();
        testShop();
        testReadAloudMerges();
        testCommands();
        testStorePersistence();
        testHandler();
        System.out.println();
        System.out.println("===== 結果: " + passed + " passed / " + failed + " failed (合計 " + (passed + failed) + ") =====");
        if (failed > 0) {
            failures.forEach(f -> System.out.println("FAILED: " + f));
            System.exit(1);
        }
    }

    // ===== 2. JSON =====
    private static void testJson() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "星燈 \"test\" \n 改行");
        original.put("count", 42L);
        original.put("ratio", 1.5);
        original.put("flag", true);
        original.put("nothing", null);
        original.put("list", List.of("a", "b", 3L));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", "値");
        original.put("nested", nested);
        String json = Json.stringify(original);
        Map<String, Object> parsed = Json.obj(Json.parse(json));
        check("json: round-trip string", "星燈 \"test\" \n 改行".equals(Json.str(parsed.get("name"))));
        check("json: round-trip number/bool/null", Json.lng(parsed.get("count"), 0) == 42
                && Json.dbl(parsed.get("ratio"), 0) == 1.5 && Json.bool(parsed.get("flag"))
                && parsed.containsKey("nothing") && parsed.get("nothing") == null);
        check("json: round-trip list/nested", Json.arr(parsed.get("list")).size() == 3
                && "値".equals(Json.str(Json.obj(parsed.get("nested")).get("inner"))));
        check("json: unicode escape parse", "星".equals(Json.parse("\"\\u661f\"")));
    }

    // ===== 3. Config =====
    private static Path writeConfig(String json) throws IOException {
        Path file = tempDir().resolve("config.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static String botJson(String id, String watching, String appEnv, String tokenEnv, long intents) {
        return "\"" + id + "\":{\"display_name\":\"BOT" + id + "\",\"watching\":\"" + watching
                + "\",\"application_id\":\"${" + appEnv + "}\",\"token_env\":\"" + tokenEnv + "\",\"intents\":" + intents + "}";
    }

    private static String threeBots(String generalWatching) {
        return "\"bots\":{"
                + botJson("general", generalWatching, "APP_G", "TOK_G", 33411) + ","
                + botJson("read_aloud_a", "言葉を紡ぎ中", "APP_A", "TOK_A", 33409) + ","
                + botJson("read_aloud_b", "声をお届け中", "APP_B", "TOK_B", 33409) + "}";
    }

    private static String threeWorkers() {
        return "\"workers\":{"
                + "\"general\":{\"worker_id\":\"general\",\"role\":\"general\",\"bot_id\":\"general\",\"secret_env\":\"SEC_G\"},"
                + "\"read_aloud_a\":{\"worker_id\":\"read_aloud_a\",\"role\":\"read-aloud\",\"bot_id\":\"read_aloud_a\",\"secret_env\":\"SEC_A\"},"
                + "\"read_aloud_b\":{\"worker_id\":\"read_aloud_b\",\"role\":\"read-aloud\",\"bot_id\":\"read_aloud_b\",\"secret_env\":\"SEC_B\"}}";
    }

    private static Map<String, String> goodEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TOK_G", "token-general-0000000000000001");
        env.put("TOK_A", "token-a-000000000000000000001");
        env.put("TOK_B", "token-b-000000000000000000001");
        env.put("APP_G", "100000000000000001");
        env.put("APP_A", "100000000000000002");
        env.put("APP_B", "100000000000000003");
        env.put("SEC_G", "secret-general-0123456789abcdef0123");
        env.put("SEC_A", "secret-a-0123456789abcdef0123456789");
        env.put("SEC_B", "secret-b-0123456789abcdef0123456789");
        return env;
    }

    private static void testConfig() throws IOException {
        // 既定値: gateway が空なら Go版と同じ既定値が入る
        Path defaultsFile = writeConfig("{" + threeBots("サーバーを見守り中") + "," + threeWorkers() + "}");
        Config defaults = Config.load(defaultsFile, false, goodEnv());
        check("config: gateway defaults", defaults.gateway.listenAddress.equals("127.0.0.1")
                && defaults.gateway.listenPort == 8787 && defaults.gateway.eventLeaseSeconds == 30
                && defaults.gateway.maxEventAttempts == 8 && defaults.gateway.interactionTtlSeconds == 900
                && defaults.gateway.eventPayloadRetentionHours == 24);
        check("config: intents (33409 lacks GUILD_MEMBERS note)", defaults.bots.get("general").intents == 33411
                && defaults.bots.get("read_aloud_a").intents == 33409 && defaults.bots.get("read_aloud_b").intents == 33409);
        check("config: strict load succeeds with full env", Config.load(defaultsFile, true, goodEnv()) != null);

        // 重複トークン
        Map<String, String> dupEnv = goodEnv();
        dupEnv.put("TOK_A", dupEnv.get("TOK_G"));
        checkThrows("config: duplicate token rejected", "must not share a BOT token",
                () -> Config.load(defaultsFile, true, dupEnv));

        // watching が 129 字
        Path longWatching = writeConfig("{" + threeBots("あ".repeat(129)) + "," + threeWorkers() + "}");
        checkThrows("config: watching >128 rejected", "at most 128 characters",
                () -> Config.load(longWatching, false, goodEnv()));

        // BOT不足 (2体)
        String twoBots = "\"bots\":{" + botJson("general", "x", "APP_G", "TOK_G", 33411) + ","
                + botJson("read_aloud_a", "y", "APP_A", "TOK_A", 33409) + "}";
        Path missing = writeConfig("{" + twoBots + "," + threeWorkers() + "}");
        checkThrows("config: missing bot rejected", "exactly 3 bots",
                () -> Config.load(missing, false, goodEnv()));

        // 短いシークレット (存在する場合は strict/lenient どちらでも >=32 を要求)
        Map<String, String> shortSecretEnv = goodEnv();
        shortSecretEnv.put("SEC_G", "short");
        checkThrows("config: short worker secret rejected", "at least 32 characters",
                () -> Config.load(defaultsFile, true, shortSecretEnv));

        // strict ではトークン必須、lenient ではオフライン利用可
        Map<String, String> noTokenEnv = goodEnv();
        noTokenEnv.remove("TOK_G");
        checkThrows("config: strict requires token", "must contain a real BOT token",
                () -> Config.load(defaultsFile, true, noTokenEnv));
        check("config: lenient allows missing token", Config.load(defaultsFile, false, noTokenEnv) != null);
    }

    // ===== 4. formatMemberMessage / resolveVoiceLogChannel =====
    private static void testFormatAndVoiceRouting() {
        String formatted = StarHandler.formatMemberMessage(
                "{user} ({username} / {user_id}) が {server} に参加", "123", "taro", "テスト鯖");
        check("format: placeholders", formatted.equals("<@123> (taro / 123) が テスト鯖 に参加"));
        String capped = StarHandler.formatMemberMessage("x".repeat(3000), "1", "u", "s");
        check("format: 2000 code point cap", capped.codePointCount(0, capped.length()) == 2000);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("log.voice", "common-ch");
        settings.put("log.voice.category.cat1", "category-ch");
        settings.put("log.voice.channel.vc1", "channel-ch");
        check("voice-log: channel beats category and common",
                StarHandler.resolveVoiceLogChannel(settings, "vc1", "cat1").equals("channel-ch"));
        check("voice-log: category beats common",
                StarHandler.resolveVoiceLogChannel(settings, "vc2", "cat1").equals("category-ch"));
        check("voice-log: common fallback",
                StarHandler.resolveVoiceLogChannel(settings, "vc3", "cat9").equals("common-ch"));
        check("voice-log: empty when unset",
                StarHandler.resolveVoiceLogChannel(new LinkedHashMap<>(), "vc1", "cat1").isEmpty());
    }

    // ===== 5. ポイント獲得 =====
    private static void testEarn() throws IOException {
        Store store = new Store(tempDir().resolve("store.json"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(store, clock, new Random(1));

        Store.PointResult first = features.earnPoints("g", "u", "e1", 1, 60, 50);
        check("earn: first message earns 1pt", first.earned() == 1 && first.balance() == 1);

        Store.PointResult cooldown = features.earnPoints("g", "u", "e2", 1, 60, 50);
        check("earn: cooldown blocks second earn", cooldown.earned() == 0 && cooldown.balance() == 1);

        clock.advance(Duration.ofSeconds(61));
        Store.PointResult afterCooldown = features.earnPoints("g", "u", "e3", 1, 60, 50);
        check("earn: earns again after cooldown", afterCooldown.earned() == 1 && afterCooldown.balance() == 2);

        Store.PointResult dedup = features.earnPoints("g", "u", "e1", 1, 60, 50);
        check("earn: ledger dedup by event_key", dedup.earned() == 0 && dedup.balance() == 2);

        // 日次上限: 30ptずつ、 上限50 → 30 + 20 + 0
        Store store2 = new Store(tempDir().resolve("store.json"));
        MutableClock clock2 = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures f2 = new StarFeatures(store2, clock2, new Random(1));
        long e1 = f2.earnPoints("g", "u", "c1", 30, 0, 50).earned();
        long e2 = f2.earnPoints("g", "u", "c2", 30, 0, 50).earned();
        long e3 = f2.earnPoints("g", "u", "c3", 30, 0, 50).earned();
        check("earn: daily cap truncates at 50", e1 == 30 && e2 == 20 && e3 == 0
                && f2.getPointAccount("g", "u").balance() == 50);
        clock2.advance(Duration.ofDays(1));
        check("earn: daily cap resets next UTC day", f2.earnPoints("g", "u", "c4", 30, 0, 50).earned() == 30);
    }

    // ===== 6. 抽選 =====
    private static void testLottery() throws IOException {
        Store store = new Store(tempDir().resolve("store.json"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));

        StarFeatures poor = new StarFeatures(store, clock, new FixedRandom(0));
        checkThrows("lottery: insufficient points", "insufficient points",
                () -> poor.drawLottery("g", "u-poor", "lot-poor", 10, 50, 20));

        StarFeatures winner = new StarFeatures(store, clock, new FixedRandom(0));
        grantPoints(winner, clock, "g", "u-win", 5); // 50pt
        StarFeatures.LotteryResult win = winner.drawLottery("g", "u-win", "lot-win", 10, 50, 20);
        check("lottery: win pays prize minus cost", win.won() && win.points().balance() == 90);

        StarFeatures.LotteryResult replay = winner.drawLottery("g", "u-win", "lot-win", 10, 50, 20);
        check("lottery: idempotent replay returns saved result", replay.won()
                && replay.points().balance() == 90 && winner.getPointAccount("g", "u-win").balance() == 90);

        StarFeatures loser = new StarFeatures(store, clock, new FixedRandom(99));
        grantPoints(loser, clock, "g", "u-lose", 5); // 50pt
        StarFeatures.LotteryResult lose = loser.drawLottery("g", "u-lose", "lot-lose", 10, 50, 20);
        check("lottery: lose deducts cost only", !lose.won() && lose.points().balance() == 40);
    }

    // ===== 7. おみくじ =====
    private static void testOmikuji() throws IOException {
        Store store = new Store(tempDir().resolve("store.json"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(store, clock, new FixedRandom(0));

        String fortune = features.drawOmikuji("g", "u", "om-1");
        check("omikuji: result is one of the six", StarFeatures.OMIKUJI_RESULTS.contains(fortune));
        check("omikuji: fixed random gives 大吉", fortune.equals("大吉"));

        String replay = features.drawOmikuji("g", "u", "om-1");
        check("omikuji: idempotent replay", replay.equals(fortune));

        checkThrows("omikuji: daily once per UTC day", "omikuji already drawn today",
                () -> features.drawOmikuji("g", "u", "om-2"));

        clock.advance(Duration.ofDays(1));
        check("omikuji: drawable again next day",
                StarFeatures.OMIKUJI_RESULTS.contains(features.drawOmikuji("g", "u", "om-3")));
    }

    // ===== 8. ショップ =====
    private static void testShop() throws IOException {
        Store store = new Store(tempDir().resolve("store.json"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(store, clock, new Random(1));

        checkThrows("shop: item_id charset validation", "ASCII letters, numbers and hyphens",
                () -> features.upsertShopItem("g", shopItem("bad_id!", "名前", 10, "r1")));
        checkThrows("shop: item_id length validation", "shop item requires",
                () -> features.upsertShopItem("g", shopItem("a".repeat(51), "名前", 10, "r1")));
        checkThrows("shop: price must be positive", "shop item requires",
                () -> features.upsertShopItem("g", shopItem("ok-id", "名前", 0, "r1")));
        checkThrows("shop: name length validation", "shop item requires",
                () -> features.upsertShopItem("g", shopItem("ok-id", "あ".repeat(101), 10, "r1")));

        features.upsertShopItem("g", shopItem("vip", "VIPロール", 30, "role-vip"));
        check("shop: list shows enabled item", features.listShopItems("g").size() == 1
                && features.listShopItems("g").get(0).itemId.equals("vip"));

        checkThrows("shop: buy not found", "shop item not found",
                () -> features.buyShopItem("g", "u1", "missing", "buy-x"));
        checkThrows("shop: buy insufficient", "insufficient points",
                () -> features.buyShopItem("g", "u1", "vip", "buy-poor"));

        grantPoints(features, clock, "g", "u1", 5); // 50pt
        StarFeatures.BuyResult bought = features.buyShopItem("g", "u1", "vip", "buy-1");
        check("shop: buy succeeds pending with charge", bought.rolePending()
                && bought.points().balance() == 20 && bought.item().roleId.equals("role-vip"));
        check("shop: inventory recorded pending",
                "pending".equals(features.getInventory("g", "u1", "vip").roleStatus));

        StarFeatures.BuyResult retry = features.buyShopItem("g", "u1", "vip", "buy-2");
        check("shop: pending retry does not double charge", retry.rolePending()
                && retry.points().balance() == 20 && features.getPointAccount("g", "u1").balance() == 20);

        StarFeatures.BuyResult sameEvent = features.buyShopItem("g", "u1", "vip", "buy-1");
        check("shop: same event_id replay does not double charge", sameEvent.rolePending()
                && sameEvent.points().balance() == 20);

        features.completeShopRoleGrant("g", "u1", "vip");
        features.completeShopRoleGrant("g", "u1", "vip"); // 冪等
        check("shop: complete grant idempotent -> fulfilled",
                "fulfilled".equals(features.getInventory("g", "u1", "vip").roleStatus));
        checkThrows("shop: already owned after fulfilled", "shop item already owned",
                () -> features.buyShopItem("g", "u1", "vip", "buy-3"));

        features.upsertShopItem("g", shopItem("rare", "レアロール", 10, "role-rare"));
        store.setShopItemEnabled("g", "rare", false);
        grantPoints(features, clock, "g", "u2", 5);
        checkThrows("shop: disabled item cannot be bought", "shop item is disabled",
                () -> features.buyShopItem("g", "u2", "rare", "buy-disabled"));
        check("shop: disabled item hidden from list",
                features.listShopItems("g").stream().noneMatch(i -> i.itemId.equals("rare")));
    }

    // ===== 9. 読み上げ設定・辞書マージ =====
    private static void testReadAloudMerges() throws IOException {
        Store store = new Store(tempDir().resolve("store.json"));
        store.putReadAloudSettings("global", "global", Map.of("enabled", true, "speed", 1.2, "speaker_id", 3L));
        store.putReadAloudSettings("guild", "g1", Map.of("speed", 1.5));
        store.putReadAloudSettings("user", "u1", Map.of("volume", 0.8));
        ReadAloud.Settings merged = ReadAloud.resolveSettings(store, "g1", "u1");
        check("read-aloud: merge global->guild->user, missing keys do not overwrite",
                merged.enabled && merged.speed == 1.5 && merged.volume == 0.8 && merged.speakerId == 3);
        ReadAloud.Settings defaults = ReadAloud.resolveSettings(store, "unknown-guild", null);
        check("read-aloud: defaults when no scope set", defaults.enabled && defaults.speed == 1.2);
        ReadAloud.Settings empty = ReadAloud.mergeSettings(List.of());
        check("read-aloud: built-in defaults", !empty.enabled && empty.speed == 1.0 && empty.volume == 1.0 && empty.speakerId == 1);

        store.upsertDictionaryEntry("global", "global", new Store.DictionaryEntry("e1", "AI", "エーアイ"));
        store.upsertDictionaryEntry("global", "global", new Store.DictionaryEntry("e2", "AI技術", "エーアイギジュツ"));
        List<Store.DictionaryEntry> entries = ReadAloud.mergeDictionary(store, "g1", null);
        String applied = ReadAloud.applyDictionary("AI技術とAI", entries);
        check("dictionary: longest word replaced first", applied.equals("エーアイギジュツとエーアイ"));

        store.upsertDictionaryEntry("user", "u1", new Store.DictionaryEntry("e3", "AI", "人工知能"));
        List<Store.DictionaryEntry> userEntries = ReadAloud.mergeDictionary(store, "g1", "u1");
        check("dictionary: user scope overrides global for same word",
                ReadAloud.applyDictionary("AI", userEntries).equals("人工知能"));

        check("read-aloud: clean text strips URLs and mentions",
                ReadAloud.cleanTextForReading("見て https://example.com/x <@123> これ")
                        .equals("見て URL省略 これ"));
    }

    // ===== 10. コマンド定義 =====
    private static List<String> commandNames(List<Map<String, Object>> commands) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> c : commands) names.add(Json.str(c.get("name")));
        return names;
    }

    private static Map<String, Object> findCommand(List<Map<String, Object>> commands, String name) {
        for (Map<String, Object> c : commands) if (name.equals(Json.str(c.get("name")))) return c;
        return null;
    }

    private static void testCommands() {
        check("commands: general bot command names", commandNames(Commands.forBot("general")).equals(
                List.of("ping", "welcome", "goodbye", "autorole", "blacklist", "logs", "sticky", "role-panel",
                        "game-config", "omikuji", "points", "lottery", "shop", "buy")));
        check("commands: read-aloud bot command names", commandNames(Commands.forBot("read_aloud_a")).equals(
                List.of("join", "leave", "say")) && commandNames(Commands.forBot("read_aloud_b")).equals(
                List.of("join", "leave", "say")));

        Map<String, Object> shop = findCommand(Commands.forBot("general"), "shop");
        List<String> shopSubs = new ArrayList<>();
        for (Object o : Json.arr(shop.get("options"))) shopSubs.add(Json.str(Json.obj(o).get("name")));
        check("commands: shop has list/add subcommands", shopSubs.equals(List.of("list", "add")));

        Map<String, Object> buy = findCommand(Commands.forBot("general"), "buy");
        Map<String, Object> buyOption = Json.obj(Json.arr(buy.get("options")).get(0));
        check("commands: buy has required item_id option", "item_id".equals(Json.str(buyOption.get("name")))
                && Json.bool(buyOption.get("required")));

        Map<String, Object> logs = findCommand(Commands.forBot("general"), "logs");
        Map<String, Object> logsSet = Json.obj(Json.arr(logs.get("options")).get(0));
        Map<String, Object> eventOption = Json.obj(Json.arr(logsSet.get("options")).get(0));
        check("commands: logs event has 9 choices", Json.arr(eventOption.get("choices")).size() == 9);

        Map<String, Object> rolePanel = findCommand(Commands.forBot("general"), "role-panel");
        Map<String, Object> create = Json.obj(Json.arr(rolePanel.get("options")).get(0));
        check("commands: role-panel create has channel/title/5 roles/5 labels",
                Json.arr(create.get("options")).size() == 12);

        check("commands: admin classification", Commands.isAdminCommand("welcome", "set")
                && Commands.isAdminCommand("shop", "add") && !Commands.isAdminCommand("shop", "list")
                && !Commands.isAdminCommand("omikuji", "") && !Commands.isAdminCommand("points", "")
                && !Commands.isAdminCommand("lottery", "") && !Commands.isAdminCommand("buy", ""));
        check("commands: JSON serialization is valid JSON",
                Json.obj(Json.parse(Commands.toJson("general"))).isEmpty()
                        && Json.arr(Json.parse(Commands.toJson("general"))).size() == 14);
    }

    // ===== 11. Store 永続化 =====
    private static void testStorePersistence() throws IOException {
        Path file = tempDir().resolve("store.json");
        Store first = new Store(file);
        first.setGuildSetting("g", "member_join", Map.of("channel_id", "ch1", "message", "hi {user}"));
        first.addBlacklist("g", "bad-user", "spam");
        Store.StickyMessage sticky = new Store.StickyMessage();
        sticky.guildId = "g";
        sticky.channelId = "ch1";
        sticky.messageId = "m1";
        sticky.content = "固定";
        first.setStickyMessage(sticky);
        first.putReadAloudSettings("guild", "g", Map.of("enabled", true));
        first.upsertDictionaryEntry("global", "global", new Store.DictionaryEntry("e1", "猫", "ねこ"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(first, clock, new FixedRandom(0));
        features.earnPoints("g", "u", "persist-earn", 5, 0, 100);
        features.upsertShopItem("g", shopItem("vip", "VIP", 3, "role-vip"));
        features.buyShopItem("g", "u", "vip", "persist-buy");
        features.drawOmikuji("g", "u", "persist-omikuji");

        Store second = new Store(file); // 再読み込み = 再起動相当
        check("store: guild settings survive reload",
                "ch1".equals(Json.str(Json.obj(second.getGuildSetting("g", "member_join")).get("channel_id"))));
        check("store: blacklist survives reload", second.isBlacklisted("g", "bad-user"));
        check("store: sticky survives reload", second.getStickyMessage("g", "ch1") != null
                && second.getStickyMessage("g", "ch1").content.equals("固定"));
        StarFeatures reloaded = new StarFeatures(second, clock, new FixedRandom(0));
        check("store: points/inventory/omikuji survive reload", reloaded.getPointAccount("g", "u").balance() == 2
                && "pending".equals(reloaded.getInventory("g", "u", "vip").roleStatus)
                && reloaded.drawOmikuji("g", "u", "persist-omikuji").equals("大吉"));
        check("store: read-aloud settings and dictionary survive reload",
                second.getReadAloudSettings("guild", "g") != null
                        && second.getDictionary("global", "global").size() == 1);
    }

    // ===== 12. StarHandler (フェイク DiscordOps) =====
    static final class RecordingOps implements StarHandler.DiscordOps {
        final List<String> sent = new ArrayList<>();
        final List<String> roles = new ArrayList<>();
        final List<String> replies = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        private int counter = 0;

        @Override public String sendMessage(String channelId, String content, Object components, String operationId) {
            sent.add(channelId + "|" + content);
            return "sent-" + (++counter);
        }

        @Override public void deleteMessage(String channelId, String messageId, String operationId) {
            deleted.add(channelId + "|" + messageId);
        }

        @Override public void addRole(String guildId, String userId, String roleId, String operationId) {
            roles.add(guildId + "|" + userId + "|" + roleId);
        }

        @Override public void removeRole(String guildId, String userId, String roleId, String operationId) {}

        @Override public void reply(StarHandler.Interaction interaction, String content) {
            replies.add(content);
        }

        @Override public void ackComponent(StarHandler.Interaction interaction) {}

        @Override public void followup(StarHandler.Interaction interaction, String content) {
            replies.add(content);
        }
    }

    private static Map<String, Object> memberPayload(String guildId, String userId, String username, List<Object> roles) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("guild_id", guildId);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userId);
        user.put("username", username);
        payload.put("user", user);
        payload.put("roles", roles);
        return payload;
    }

    private static void testHandler() throws Exception {
        Store store = new Store(tempDir().resolve("store.json"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(store, clock, new FixedRandom(0));
        RecordingOps ops = new RecordingOps();
        StarHandler handler = new StarHandler(store, features, ops);

        Map<String, Object> guild = new LinkedHashMap<>();
        guild.put("id", "g1");
        guild.put("name", "テスト鯖");
        handler.handleEvent(new StarHandler.Event("ge", "GUILD_CREATE", guild));

        store.setGuildSetting("g1", "member_join", Map.of("channel_id", "welcome-ch", "message", "{user} ようこそ {server} へ"));
        store.setGuildSetting("g1", "autorole", Map.of("role_id", "role-new"));
        store.setGuildSetting("g1", "log.member_join", "log-ch");
        store.setGuildSetting("g1", "log.autorole_refused", "log-ch");

        handler.handleEvent(new StarHandler.Event("join1", "GUILD_MEMBER_ADD", memberPayload("g1", "u1", "taro", List.of())));
        check("handler: welcome message formatted and sent",
                ops.sent.stream().anyMatch(s -> s.equals("welcome-ch|<@u1> ようこそ テスト鯖 へ")));
        check("handler: autorole granted on join", ops.roles.contains("g1|u1|role-new"));
        check("handler: member join logged", ops.sent.stream().anyMatch(s -> s.startsWith("log-ch|参加: taro")));

        // ブラックリスト対象者は自動ロール拒否 + ログ
        store.addBlacklist("g1", "bad", "reason");
        int rolesBefore = ops.roles.size();
        handler.handleEvent(new StarHandler.Event("join2", "GUILD_MEMBER_ADD", memberPayload("g1", "bad", "evil", List.of())));
        check("handler: blacklisted member gets no autorole", ops.roles.size() == rolesBefore);
        check("handler: blacklist refusal logged",
                ops.sent.stream().anyMatch(s -> s.startsWith("log-ch|自動ロール付与を拒否しました")));

        // スティッキー: 通常メッセージで最下部へ再投稿される
        Store.StickyMessage sticky = new Store.StickyMessage();
        sticky.guildId = "g1";
        sticky.channelId = "chat";
        sticky.messageId = "old-sticky";
        sticky.content = "固定文";
        store.setStickyMessage(sticky);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "new-msg");
        message.put("guild_id", "g1");
        message.put("channel_id", "chat");
        message.put("author", Map.of("id", "u1", "username", "taro"));
        message.put("content", "hello");
        handler.handleEvent(new StarHandler.Event("msg1", "MESSAGE_CREATE", message));
        check("handler: sticky reposted on message create",
                ops.sent.stream().anyMatch(s -> s.equals("chat|固定文")) && ops.deleted.contains("chat|old-sticky"));
        check("handler: sticky message id updated", !store.getStickyMessage("g1", "chat").messageId.equals("old-sticky"));
        check("handler: message create earns points", features.getPointAccount("g1", "u1").balance() == 1);

        // スティッキー自体が削除されたら再投稿
        String currentStickyId = store.getStickyMessage("g1", "chat").messageId;
        Map<String, Object> deletion = new LinkedHashMap<>();
        deletion.put("id", currentStickyId);
        deletion.put("guild_id", "g1");
        deletion.put("channel_id", "chat");
        int sentBefore = ops.sent.size();
        handler.handleEvent(new StarHandler.Event("del1", "MESSAGE_DELETE", deletion));
        check("handler: sticky reposted when its message is deleted", ops.sent.size() > sentBefore);

        // 非管理者は管理コマンドを実行できない
        StarHandler.Interaction adminCommand = new StarHandler.Interaction();
        adminCommand.id = "int-admin";
        adminCommand.type = 2;
        adminCommand.guildId = "g1";
        adminCommand.userId = "u9";
        adminCommand.permissions = "0";
        adminCommand.commandName = "welcome";
        adminCommand.subcommandName = "disable";
        handler.respondToCommand(adminCommand);
        check("handler: non-admin blocked from admin command",
                ops.replies.stream().anyMatch(r -> r.contains("サーバー管理")));

        // 管理者判定ビット
        StarHandler.Interaction admin = new StarHandler.Interaction();
        admin.permissions = "8";
        StarHandler.Interaction manager = new StarHandler.Interaction();
        manager.permissions = "32";
        StarHandler.Interaction plain = new StarHandler.Interaction();
        plain.permissions = "0";
        check("handler: admin bits ADMINISTRATOR|MANAGE_GUILD",
                StarHandler.isGuildAdmin(admin) && StarHandler.isGuildAdmin(manager) && !StarHandler.isGuildAdmin(plain));

        // VCログ: チャンネル別設定が優先される
        store.setGuildSetting("g1", "log.voice", "vc-common");
        store.setGuildSetting("g1", "log.voice.channel.vc9", "vc-special");
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("guild_id", "g1");
        voice.put("user_id", "u1");
        voice.put("channel_id", "vc9");
        handler.handleEvent(new StarHandler.Event("voice1", "VOICE_STATE_UPDATE", voice));
        check("handler: voice join logged to channel-specific channel",
                ops.sent.stream().anyMatch(s -> s.startsWith("vc-special|VC参加: <@u1>")));

        // ロールパネル: 未登録パネルは拒否、登録済みは付与
        StarHandler.Interaction component = new StarHandler.Interaction();
        component.id = "comp1";
        component.type = 3;
        component.guildId = "g1";
        component.userId = "u1";
        component.messageId = "panel-msg";
        component.customId = "star-role:777";
        handler.handleComponent(component);
        check("handler: unregistered role panel rejected",
                ops.replies.stream().anyMatch(r -> r.contains("パネルに登録されていません")));
        store.setGuildSetting("g1", "role-panel:panel-msg", List.of("777"));
        handler.handleComponent(component);
        check("handler: registered role panel grants role", ops.roles.contains("g1|u1|777"));
    }
}
