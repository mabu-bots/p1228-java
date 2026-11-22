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
        testAiHandler();
        testAiClient();
        testEarnSkipsSaveWhenUnchanged();
        testIdempotencyTrimming();
        testHeartbeatMonitor();
        testSessionWatchdog();
        testRetryDelay();
        testGatewayParams();
        testPresence();
        testRestRetries();
        testFatalCloseCodes();
        testExecLog();
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

        // ローカルLLM の URL: 既定値と JSON 上書き
        check("config: llama base url default", defaults.llamaBaseUrl.equals("http://127.0.0.1:8080"));
        check("config: llama base url default in defaultConfigJson",
                Config.load(null, false, goodEnv()).llamaBaseUrl.equals("http://127.0.0.1:8080"));
        Path llamaFile = writeConfig("{" + threeBots("サーバーを見守り中") + "," + threeWorkers()
                + ",\"llama_base_url\":\"http://127.0.0.1:9999\"}");
        check("config: llama base url override from JSON",
                Config.load(llamaFile, false, goodEnv()).llamaBaseUrl.equals("http://127.0.0.1:9999"));

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
                        "game-config", "omikuji", "points", "lottery", "shop", "buy", "ai")));
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
        check("commands: ai is not an admin command", !Commands.isAdminCommand("ai", ""));

        Map<String, Object> ai = findCommand(Commands.forBot("general"), "ai");
        check("commands: general has 15 commands including ai",
                Commands.generalCommands().size() == 15 && ai != null);
        Map<String, Object> promptOption = ai == null ? null : Json.obj(Json.arr(ai.get("options")).get(0));
        check("commands: ai has required prompt option (max_length 500)", promptOption != null
                && "prompt".equals(Json.str(promptOption.get("name")))
                && Json.lng(promptOption.get("type"), 0) == 3
                && Json.bool(promptOption.get("required"))
                && Json.lng(promptOption.get("max_length"), 0) == 500);

        check("commands: JSON serialization is valid JSON",
                Json.obj(Json.parse(Commands.toJson("general"))).isEmpty()
                        && Json.arr(Json.parse(Commands.toJson("general"))).size() == 15);
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
        final List<String> flow = new ArrayList<>();
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

        @Override public void deferReply(StarHandler.Interaction interaction) {
            flow.add("DEFER_REPLY");
        }

        @Override public void editOriginal(StarHandler.Interaction interaction, String content) {
            flow.add("EDIT_ORIGINAL|" + content);
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
        StarHandler handler = new StarHandler(store, features, ops, null);

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

    // ===== 13. StarHandler の /ai (フェイク AiChat) =====
    private static StarHandler.Interaction aiInteraction(String prompt) {
        StarHandler.Interaction it = new StarHandler.Interaction();
        it.id = "int-ai";
        it.type = 2;
        it.guildId = "g1";
        it.userId = "u1";
        it.permissions = "0";
        it.commandName = "ai";
        if (prompt != null) it.options.put("prompt", prompt);
        return it;
    }

    private static void testAiHandler() throws Exception {
        Store store = new Store(tempDir().resolve("store.json"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(store, clock, new FixedRandom(0));

        // 成功: defer の後に editOriginal で回答が届く
        RecordingOps okOps = new RecordingOps();
        StarHandler okHandler = new StarHandler(store, features, okOps, prompt -> "AIの回答です");
        okHandler.respondToCommand(aiInteraction("星について教えて"));
        check("ai: success defers then edits original with the answer",
                okOps.flow.equals(List.of("DEFER_REPLY", "EDIT_ORIGINAL|AIの回答です")));

        // 長い回答は 2000 code point に切り詰められる
        RecordingOps longOps = new RecordingOps();
        StarHandler longHandler = new StarHandler(store, features, longOps, prompt -> "あ".repeat(3000));
        longHandler.respondToCommand(aiInteraction("長く答えて"));
        String edited = longOps.flow.size() == 2 ? longOps.flow.get(1) : "";
        check("ai: long answer truncated to 2000 code points",
                edited.startsWith("EDIT_ORIGINAL|")
                        && edited.substring("EDIT_ORIGINAL|".length()).codePointCount(0, edited.length() - "EDIT_ORIGINAL|".length()) == 2000);

        // 失敗: editOriginal で失敗メッセージが届く
        RecordingOps failOps = new RecordingOps();
        StarHandler failHandler = new StarHandler(store, features, failOps,
                prompt -> { throw new IllegalStateException("connection refused"); });
        failHandler.respondToCommand(aiInteraction("質問"));
        check("ai: failure edits original with the failure message",
                failOps.flow.equals(List.of("DEFER_REPLY",
                        "EDIT_ORIGINAL|AIの応答取得に失敗しました。時間をおいて再度お試しください。")));
        check("ai: failure is logged", failHandler.logLines.stream()
                .anyMatch(l -> l.startsWith("ai_chat_failed") && l.contains("connection refused")));

        // aiChat が null: 通常 reply で無効メッセージ、defer はしない
        RecordingOps nullOps = new RecordingOps();
        StarHandler nullHandler = new StarHandler(store, features, nullOps, null);
        nullHandler.respondToCommand(aiInteraction("質問"));
        check("ai: disabled message via reply when aiChat is null",
                nullOps.replies.equals(List.of("AI機能は現在有効化されていません。")) && nullOps.flow.isEmpty());

        // prompt が空: 通常 reply で入力を促す
        RecordingOps emptyOps = new RecordingOps();
        StarHandler emptyHandler = new StarHandler(store, features, emptyOps, prompt -> "使われない");
        emptyHandler.respondToCommand(aiInteraction(""));
        check("ai: empty prompt asks for input via reply",
                emptyOps.replies.equals(List.of("質問を入力してください。")) && emptyOps.flow.isEmpty());
    }

    // ===== 14. AiClient (ローカルスタブHTTPサーバー) =====
    // 開発VMのように Java プロセスの直接TCPが遮断される環境では、
    // スタブへの疎通自体が成立しないため、その場合だけテストを
    // スキップする (通常の環境では常に実行される)。疎通確認は、
    // 対象でないパスへの GET に対する応答が HTTP ステータス行から
    // 始まるかどうかで判定する (遮断時はプロキシの拒否文が返る)。
    private static boolean stubReachable(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /__probe__ HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            byte[] head = socket.getInputStream().readNBytes(5);
            return head.length == 5 && "HTTP/".equals(new String(head, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static void testAiClient() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);
            String content = requestBody.contains("空応答") ? "" : "こんにちは、星燈AIです。";
            String responseJson = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                    + content + "\"}}]}";
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        if (!stubReachable(server.getAddress().getPort())) {
            System.out.println("SKIP: ai-client (この環境では Java プロセスの TCP 接続が遮断されているため、スタブ疎通を伴う検証を省略)");
            server.stop(0);
            return;
        }
        try {
            AiClient client = new AiClient("http://127.0.0.1:" + server.getAddress().getPort());
            String answer = client.chat("おすすめの星は？");
            check("ai-client: returns choices[0].message.content", "こんにちは、星燈AIです。".equals(answer));
            check("ai-client: request body carries system and user messages",
                    requestBodies.size() == 1 && requestBodies.get(0).contains("おすすめの星は？")
                            && requestBodies.get(0).contains(AiClient.SYSTEM_PROMPT)
                            && requestBodies.get(0).contains("\"max_tokens\":400"));
            checkThrows("ai-client: empty content is an error", "empty response",
                    () -> client.chat("空応答で返して"));
        } finally {
            server.stop(0);
        }
    }

    // ===== 15. ポイント保存の省略 (レビューM-1) =====
    private static void testEarnSkipsSaveWhenUnchanged() throws Exception {
        java.nio.file.Path file = tempDir().resolve("store.json");
        Store store = new Store(file);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(store, clock, new Random(1));
        features.earnPoints("g", "u", "s1", 1, 60, 50);
        byte[] before = java.nio.file.Files.readAllBytes(file);
        Store.PointResult result = features.earnPoints("g", "u", "s2", 1, 60, 50);
        byte[] after = java.nio.file.Files.readAllBytes(file);
        check("earn: no file rewrite when nothing earned (cooldown)",
                result.earned() == 0 && java.util.Arrays.equals(before, after));
    }

    // ===== 16. 冪等記録の間引き (レビューM-1) =====
    private static void testIdempotencyTrimming() throws Exception {
        Store store = new Store(tempDir().resolve("store.json"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StarFeatures features = new StarFeatures(store, clock, new Random(1));
        int limit = StarFeatures.IDEMPOTENCY_CACHE_LIMIT;

        for (int i = 0; i < limit + 10; i++) store.pointLedger.put("dummy:" + i, Map.of());
        features.earnPoints("g", "u", "trim-target", 1, 0, 50);
        check("trim: point ledger capped at limit", store.pointLedger.size() <= limit);
        check("trim: newest ledger entry kept, oldest dropped",
                store.pointLedger.containsKey("earn:trim-target") && !store.pointLedger.containsKey("dummy:0"));

        for (int i = 0; i < limit + 10; i++) store.starActions.put("dummy:" + i, "{}");
        grantPoints(features, clock, "g", "u2", 1);
        features.drawLottery("g", "u2", "trim-lottery", 10, 50, 20);
        check("trim: star actions capped at limit", store.starActions.size() <= limit
                && store.starActions.containsKey("lottery:trim-lottery"));

        store.omikujiDraws.put(Store.key("g", "old-user", "2000-01-01"), "吉");
        features.drawOmikuji("g", "u3", "trim-omikuji");
        check("trim: old omikuji draws pruned",
                !store.omikujiDraws.containsKey(Store.key("g", "old-user", "2000-01-01")));
    }

    // ===== 17. ハートビートACK監視 (レビューM-2) =====
    private static void testHeartbeatMonitor() {
        GatewayClient.HeartbeatMonitor monitor = new GatewayClient.HeartbeatMonitor();
        check("heartbeat: fresh monitor is not zombie", !monitor.isZombie(10_000, 1000));
        monitor.onSend(0);
        check("heartbeat: not zombie before interval elapses", !monitor.isZombie(999, 1000));
        check("heartbeat: zombie when ACK missing for an interval", monitor.isZombie(1000, 1000));
        monitor.onAck();
        check("heartbeat: ACK clears zombie state", !monitor.isZombie(5000, 1000));
        monitor.onSend(5000);
        check("heartbeat: zombie again after next unacked send", monitor.isZombie(6000, 1000));
        monitor.reset();
        check("heartbeat: reset clears zombie state", !monitor.isZombie(999_999, 1000));
    }

    // ===== 18. 429 リトライ待機 (レビューM-3) =====
    private static void testRetryDelay() {
        check("retry: sub-second delay kept", DiscordRest.clampRetryDelayMillis(0.25) == 250);
        check("retry: long delay capped at 5000ms", DiscordRest.clampRetryDelayMillis(30.0) == 5000);
        check("retry: missing value defaults to 1000ms", DiscordRest.clampRetryDelayMillis(-1) == 1000);
        check("retry: zero delay stays zero", DiscordRest.clampRetryDelayMillis(0) == 0);
    }

    // ===== 19. セッション確立ウォッチドッグ (Issue #4) =====
    private static void testSessionWatchdog() {
        GatewayClient.SessionWatchdog watchdog = new GatewayClient.SessionWatchdog();
        check("session: fresh watchdog is not expired", !watchdog.isExpired(999_999, 30_000));
        watchdog.onAttempt(0);
        check("session: not expired before timeout", !watchdog.isExpired(29_999, 30_000));
        check("session: expired when READY missing past timeout", watchdog.isExpired(30_000, 30_000));
        watchdog.onEstablished();
        check("session: established clears expiry", !watchdog.isExpired(999_999, 30_000));
        watchdog.onAttempt(100_000);
        check("session: new attempt re-arms the deadline", !watchdog.isExpired(129_999, 30_000));
        check("session: new attempt expires at its own deadline", watchdog.isExpired(130_000, 30_000));
        watchdog.reset();
        check("session: reset clears expiry", !watchdog.isExpired(999_999, 30_000));
    }

    // ===== 20. Gateway URL のパラメータ補完 (Resume 障害の修正) =====
    // resume_gateway_url は v/encoding 指定を含まないため、そのまま使うと
    // Resume が成立しない。connect 前に必ず補完されることを固定する。
    private static void testGatewayParams() {
        check("gateway-params: resume url gains params",
                GatewayClient.withGatewayParams("wss://gateway-us-east1-b.discord.gg")
                        .equals("wss://gateway-us-east1-b.discord.gg?v=10&encoding=json"));
        check("gateway-params: primary url unchanged",
                GatewayClient.withGatewayParams("wss://gateway.discord.gg/?v=10&encoding=json")
                        .equals("wss://gateway.discord.gg/?v=10&encoding=json"));
        check("gateway-params: existing query uses ampersand",
                GatewayClient.withGatewayParams("wss://example.gg/?foo=bar")
                        .equals("wss://example.gg/?foo=bar&v=10&encoding=json"));
        check("gateway-params: null stays null", GatewayClient.withGatewayParams(null) == null);
    }

    // ===== 21. プレゼンス統一形式 (Watching「/help | {遅延}ms」) =====
    private static void testPresence() {
        check("presence: name carries latency", GatewayClient.presenceName(42).equals("/help | 42ms"));
        check("presence: zero latency", GatewayClient.presenceName(0).equals("/help | 0ms"));
        check("presence: negative latency clamped to zero", GatewayClient.presenceName(-5).equals("/help | 0ms"));

        // 遅延は Heartbeat 送信→ACK (op 11) 受信の往復時間
        GatewayClient.HeartbeatMonitor monitor = new GatewayClient.HeartbeatMonitor();
        check("presence: latency is zero before measurement", monitor.latencyMillis() == 0);
        monitor.onSend(1_000);
        monitor.onAck(1_042);
        check("presence: latency measured from heartbeat round trip", monitor.latencyMillis() == 42);
        monitor.reset();
        check("presence: reset clears latency", monitor.latencyMillis() == 0);

        // Presence Update (op 3) / Identify 共用の d の構造
        Map<String, Object> data = GatewayClient.presenceData(42);
        check("presence: status online", "online".equals(Json.str(data.get("status"))));
        check("presence: not afk", !Json.bool(data.get("afk")));
        Object activities = data.get("activities");
        check("presence: single activity", activities instanceof List<?> list && list.size() == 1);
        Map<String, Object> activity = Json.obj(((List<?>) activities).get(0));
        check("presence: activity name unified", "/help | 42ms".equals(Json.str(activity.get("name"))));
        check("presence: activity type is watching (3)", Json.lng(activity.get("type"), -1) == 3);
    }

    // ===== 22. Discord REST のリトライ (5xx / ネットワークエラー) =====
    // Aegis Discord Bot v1.5.2 と同じ意味論: 5xx と IOException のみ
    // 最大3回まで再試行し、待機は 1秒 → 3秒。4xx は即失敗、429 は
    // retry_after で1回だけ再送する。送信と待機を差し替えて検証する。
    private static java.net.http.HttpResponse<String> fakeResponse(int status, String body) {
        return new java.net.http.HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() {
                return java.util.Optional.empty();
            }
            @Override public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(Map.of(), (name, value) -> true);
            }
            @Override public String body() { return body; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                return java.util.Optional.empty();
            }
            @Override public java.net.URI uri() { return java.net.URI.create("https://example.invalid/"); }
            @Override public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }
        };
    }

    // 応答 (HttpResponse) と失敗 (IOException) を順に返す送信モック。
    private static final class ScriptedAttempt implements DiscordRest.SendAttempt {
        private final java.util.ArrayDeque<Object> outcomes = new java.util.ArrayDeque<>();
        private int calls = 0;

        ScriptedAttempt(Object... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        int calls() { return calls; }

        @Override
        public java.net.http.HttpResponse<String> send() throws Exception {
            calls++;
            Object outcome = outcomes.poll();
            if (outcome == null) throw new IllegalStateException("script exhausted");
            if (outcome instanceof IOException io) throw io;
            return (java.net.http.HttpResponse<String>) outcome;
        }
    }

    private static void testRestRetries() throws Exception {
        // リトライ対象ステータスと待機時間の純粋関数
        check("rest-retry: 500 is retryable", DiscordRest.isRetryableStatus(500));
        check("rest-retry: 503 is retryable", DiscordRest.isRetryableStatus(503));
        check("rest-retry: 599 is retryable", DiscordRest.isRetryableStatus(599));
        check("rest-retry: 200 is not retryable", !DiscordRest.isRetryableStatus(200));
        check("rest-retry: 400 is not retryable", !DiscordRest.isRetryableStatus(400));
        check("rest-retry: 404 is not retryable", !DiscordRest.isRetryableStatus(404));
        check("rest-retry: 429 handled separately, not 5xx-retryable", !DiscordRest.isRetryableStatus(429));
        check("rest-retry: backoff after 1st failure is 1000ms", DiscordRest.retryBackoffMillis(1) == 1000);
        check("rest-retry: backoff after 2nd failure is 3000ms", DiscordRest.retryBackoffMillis(2) == 3000);
        check("rest-retry: backoff stays 3000ms afterwards", DiscordRest.retryBackoffMillis(3) == 3000);
        check("rest-retry: max attempts is 3", DiscordRest.MAX_ATTEMPTS == 3);

        // 5xx → 成功: 2回送信し、待機は1秒
        List<Long> sleeps = new ArrayList<>();
        ScriptedAttempt attempt = new ScriptedAttempt(fakeResponse(503, ""), fakeResponse(200, "{}"));
        var response = DiscordRest.sendWithRetries(attempt, sleeps::add);
        check("rest-retry: 503 then 200 returns 200", response.statusCode() == 200);
        check("rest-retry: 503 then 200 sent twice", attempt.calls() == 2);
        check("rest-retry: 503 then 200 waited 1000ms", sleeps.equals(List.of(1000L)));

        // 5xx が続く: 最大3回で打ち切り、最後の応答を返す (request が例外化する)
        sleeps.clear();
        attempt = new ScriptedAttempt(fakeResponse(500, ""), fakeResponse(502, ""), fakeResponse(503, ""));
        response = DiscordRest.sendWithRetries(attempt, sleeps::add);
        check("rest-retry: persistent 5xx returns last response", response.statusCode() == 503);
        check("rest-retry: persistent 5xx stops at 3 attempts", attempt.calls() == 3);
        check("rest-retry: persistent 5xx waited 1000ms then 3000ms", sleeps.equals(List.of(1000L, 3000L)));

        // 4xx は即失敗 (再送しない)
        sleeps.clear();
        attempt = new ScriptedAttempt(fakeResponse(404, ""));
        response = DiscordRest.sendWithRetries(attempt, sleeps::add);
        check("rest-retry: 404 returned without retry", response.statusCode() == 404 && attempt.calls() == 1);
        check("rest-retry: 404 does not wait", sleeps.isEmpty());

        // ネットワークエラー → 成功
        sleeps.clear();
        attempt = new ScriptedAttempt(new IOException("connection reset"), fakeResponse(200, "{}"));
        response = DiscordRest.sendWithRetries(attempt, sleeps::add);
        check("rest-retry: IO error then 200 returns 200", response.statusCode() == 200);
        check("rest-retry: IO error then 200 sent twice", attempt.calls() == 2);
        check("rest-retry: IO error waited 1000ms", sleeps.equals(List.of(1000L)));

        // ネットワークエラーが続く: 最大3回で例外を投げる
        ScriptedAttempt failing = new ScriptedAttempt(
                new IOException("boom1"), new IOException("boom2"), new IOException("boom3"));
        checkThrows("rest-retry: persistent IO error throws after 3 attempts", "boom3",
                () -> DiscordRest.sendWithRetries(failing, millis -> { }));
        check("rest-retry: persistent IO error attempted 3 times", failing.calls() == 3);

        // 429 は retry_after で1回だけ再送する (従来動作)
        sleeps.clear();
        attempt = new ScriptedAttempt(fakeResponse(429, "{\"retry_after\": 0.5}"), fakeResponse(200, "{}"));
        response = DiscordRest.sendWithRetries(attempt, sleeps::add);
        check("rest-retry: 429 then 200 returns 200", response.statusCode() == 200);
        check("rest-retry: 429 resent once", attempt.calls() == 2);
        check("rest-retry: 429 waited retry_after 500ms", sleeps.equals(List.of(500L)));

        // 429 が続いても再送は1回まで
        attempt = new ScriptedAttempt(fakeResponse(429, "{\"retry_after\": 0}"), fakeResponse(429, "{\"retry_after\": 0}"));
        response = DiscordRest.sendWithRetries(attempt, millis -> { });
        check("rest-retry: second 429 is not resent", response.statusCode() == 429 && attempt.calls() == 2);
    }

    // ===== 23. Gateway 致命的クローズコード =====
    // トークン失効 (4004) とインテント不許可 (4013 / 4014) 等は再接続
    // しても回復しないため、停止判定が true になることを固定する。
    private static void testFatalCloseCodes() {
        check("fatal-close: 4004 authentication failed", GatewayClient.isFatalCloseCode(4004));
        check("fatal-close: 4010 invalid shard", GatewayClient.isFatalCloseCode(4010));
        check("fatal-close: 4011 sharding required", GatewayClient.isFatalCloseCode(4011));
        check("fatal-close: 4012 invalid API version", GatewayClient.isFatalCloseCode(4012));
        check("fatal-close: 4013 invalid intents", GatewayClient.isFatalCloseCode(4013));
        check("fatal-close: 4014 disallowed intents", GatewayClient.isFatalCloseCode(4014));
        check("fatal-close: 1000 normal closure is not fatal", !GatewayClient.isFatalCloseCode(1000));
        check("fatal-close: 1001 going away is not fatal", !GatewayClient.isFatalCloseCode(1001));
        check("fatal-close: 4000 unknown error is not fatal", !GatewayClient.isFatalCloseCode(4000));
        check("fatal-close: 4007 invalid seq is not fatal", !GatewayClient.isFatalCloseCode(4007));
        check("fatal-close: 4008 rate limited is not fatal", !GatewayClient.isFatalCloseCode(4008));
        check("fatal-close: 4009 session timeout is not fatal", !GatewayClient.isFatalCloseCode(4009));
        check("fatal-close: reason names authentication failure",
                GatewayClient.fatalCloseReason(4004).contains("認証失敗"));
        check("fatal-close: reason names disallowed intents",
                GatewayClient.fatalCloseReason(4014).contains("許可されていないインテント"));
    }

    // ===== 24. コマンド実行ログ (ExecLog) =====
    // 書式: [exec] <ISO時刻> cmd=<名> route=slash guild=<ID> channel=<ID>
    //       user=<ID> result=<ok|error>。入力テキストは構造上含まれない。
    private static void testExecLog() {
        java.time.Instant now = java.time.Instant.parse("2026-11-23T00:00:00Z");
        check("exec-log: ok line format",
                ExecLog.format(now, "ping", "g1", "c1", "u1", null)
                        .equals("[exec] 2026-11-23T00:00:00Z cmd=ping route=slash guild=g1 channel=c1 user=u1 result=ok"));
        check("exec-log: error line carries summary",
                ExecLog.format(now, "ai", "g1", "c1", "u1", "connection refused")
                        .equals("[exec] 2026-11-23T00:00:00Z cmd=ai route=slash guild=g1 channel=c1 user=u1 result=error error=\"connection refused\""));
        check("exec-log: blank ids become dash",
                ExecLog.format(now, "ping", "", "", "", null)
                        .equals("[exec] 2026-11-23T00:00:00Z cmd=ping route=slash guild=- channel=- user=- result=ok"));
        check("exec-log: null error message becomes unknown error",
                ExecLog.format(now, "ping", "g1", "c1", "u1", "")
                        .contains("error=\"unknown error\""));
        // 改行と引用符を含む失敗メッセージでも1行に収まり、引用符で書式が壊れない
        String messy = ExecLog.format(now, "say", "g1", "c1", "u1", "line1\nline2 \"quoted\"");
        check("exec-log: error summary stays on one line", !messy.contains("\n") && !messy.contains("line2 \"quoted\""));
        check("exec-log: error summary replaces quotes", messy.contains("error=\"line1 line2 'quoted'\""));
        // 長い失敗メッセージは切り詰められる
        String longLine = ExecLog.format(now, "ai", "g1", "c1", "u1", "x".repeat(300));
        check("exec-log: long error is truncated", longLine.length() < 260);
        // 入力テキスト (プロンプト等) を渡す引数は無く、行に混入しない
        check("exec-log: line has no free-text field",
                !ExecLog.format(now, "ai", "g1", "c1", "u1", null).contains("prompt"));
    }
}
