package p1228;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// ===== 1. StarFeatures (star_features.go のゲーム・ショップ論理) =====
// ポイント獲得・抽選・おみくじ・ショップ購入の意味論を Go 版
// gateway/internal/storage/star_features.go から忠実に移植する。
// Clock と Random を注入可能にし、オフラインで決定的に検証できる。
public final class StarFeatures {

    // ----- 1.1 型定義 -----
    public record GameSettings(long pointsPerMessage, long cooldownSeconds, long dailyLimit,
                               long lotteryCost, long lotteryPrize, long lotteryChance) {
        public static GameSettings defaults() {
            return new GameSettings(1, 60, 50, 10, 50, 20);
        }

        public static GameSettings fromSettingValue(Object value) {
            GameSettings d = defaults();
            if (!(value instanceof Map)) return d;
            Map<String, Object> m = Json.obj(value);
            return new GameSettings(
                    Json.lng(m.get("points_per_message"), d.pointsPerMessage),
                    Json.lng(m.get("cooldown_seconds"), d.cooldownSeconds),
                    Json.lng(m.get("daily_limit"), d.dailyLimit),
                    Json.lng(m.get("lottery_cost"), d.lotteryCost),
                    Json.lng(m.get("lottery_prize"), d.lotteryPrize),
                    Json.lng(m.get("lottery_chance"), d.lotteryChance));
        }

        public Map<String, Object> toSettingValue() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("points_per_message", pointsPerMessage);
            m.put("cooldown_seconds", cooldownSeconds);
            m.put("daily_limit", dailyLimit);
            m.put("lottery_cost", lotteryCost);
            m.put("lottery_prize", lotteryPrize);
            m.put("lottery_chance", lotteryChance);
            return m;
        }
    }

    public record LotteryResult(Store.PointResult points, boolean won) {}

    public record BuyResult(Store.ShopItem item, Store.PointResult points, boolean rolePending) {}

    public static final List<String> OMIKUJI_RESULTS = List.of("大吉", "中吉", "小吉", "吉", "末吉", "凶");

    private final Store store;
    private final Clock clock;
    private final Random random;

    public StarFeatures(Store store, Clock clock, Random random) {
        this.store = store;
        this.clock = clock;
        this.random = random;
    }

    public Store store() {
        return store;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private String todayUtc() {
        return LocalDate.ofInstant(now(), ZoneOffset.UTC).toString();
    }

    private void addLedger(String eventKey, String guildId, String userId, long delta, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event_key", eventKey);
        entry.put("guild_id", guildId);
        entry.put("user_id", userId);
        entry.put("delta", delta);
        entry.put("reason", reason);
        entry.put("created_at", now().toString());
        store.pointLedger.put(eventKey, entry);
    }

    // ----- 1.2 冪等記録の保持上限 (レビューM-1) -----
    // 台帳・アクション記録はインタラクション再送や Resume 再生への備えで、
    // 保持すべきは直近分だけでよい。上限なく増え続けると store.json が
    // 肥大化し保存も重くなるため、上限を超えたら古いものから間引く
    // (LinkedHashMap の挿入順 = 古い順)。
    static final int IDEMPOTENCY_CACHE_LIMIT = 5000;

    private static void trimOldest(Map<String, ?> map) {
        if (map.size() <= IDEMPOTENCY_CACHE_LIMIT) return;
        var iterator = map.keySet().iterator();
        while (map.size() > IDEMPOTENCY_CACHE_LIMIT && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void afterIdempotentWrite() {
        trimOldest(store.pointLedger);
        trimOldest(store.starActions);
    }

    // おみくじ記録のキーは guild|user|日付(UTC) で、当日分以外は判定に
    // 使わないため、7日より古い日付分を間引く。
    private void pruneOldOmikujiDraws() {
        String cutoff = LocalDate.ofInstant(now(), ZoneOffset.UTC).minusDays(7).toString();
        store.omikujiDraws.keySet().removeIf(k -> {
            int sep = k.lastIndexOf('|');
            return sep >= 0 && k.substring(sep + 1).compareTo(cutoff) < 0;
        });
    }

    // ===== 2. ポイント獲得 =====
    public Store.PointResult getPointAccount(String guildId, String userId) {
        synchronized (store) {
            Store.PointAccount account = store.pointAccounts.get(Store.key(guildId, userId));
            return new Store.PointResult(account == null ? 0 : account.balance, 0);
        }
    }

    // Go版 EarnPoints: 台帳の event_key `earn:<eventId>` で重複排除し、
    // クールダウンと UTC 日次上限を適用する。上限到達分は切り捨てる。
    public Store.PointResult earnPoints(String guildId, String userId, String eventId,
                                        long points, long cooldownSeconds, long dailyLimit) {
        if (guildId.isBlank() || userId.isBlank() || eventId.isBlank() || points < 1 || cooldownSeconds < 0 || dailyLimit < 1) {
            throw new IllegalArgumentException("invalid point earning parameters");
        }
        synchronized (store) {
            Instant current = now();
            String date = todayUtc();
            Store.PointAccount account = store.pointAccounts.computeIfAbsent(
                    Store.key(guildId, userId), k -> new Store.PointAccount());
            long earnedToday = account.earnedDate.equals(date) ? account.earnedToday : 0;
            boolean alreadyRecorded = store.pointLedger.containsKey("earn:" + eventId);
            boolean allowed = !alreadyRecorded && earnedToday < dailyLimit;
            if (allowed && !account.lastEarnedAt.isBlank()) {
                Instant last = Instant.parse(account.lastEarnedAt);
                allowed = ChronoUnit.SECONDS.between(last, current) >= cooldownSeconds;
            }
            long earned = 0;
            if (allowed) {
                long grant = Math.min(points, dailyLimit - earnedToday);
                if (grant > 0) {
                    addLedger("earn:" + eventId, guildId, userId, grant, "message reward");
                    account.balance += grant;
                    account.earnedToday = earnedToday + grant;
                    account.earnedDate = date;
                    account.lastEarnedAt = current.toString();
                    earned = grant;
                }
            }
            // 状態が変わったときだけ保存する (レビューM-1)。クールダウン等で
            // 獲得0の回まで毎回 store.json 全体を書き直すのは無駄なため。
            if (earned > 0) {
                trimOldest(store.pointLedger);
                store.save();
            }
            return new Store.PointResult(account.balance, earned);
        }
    }

    // ===== 3. 抽選 =====
    // Go版 DrawLottery: event_id で冪等リプレイ。残高不足は
    // "insufficient points" エラー。当選判定は 0..99 < chance。
    public LotteryResult drawLottery(String guildId, String userId, String eventId,
                                      long cost, long prize, long chance) {
        if (guildId.isBlank() || userId.isBlank() || eventId.isBlank() || cost < 1 || prize < 0 || chance < 0 || chance > 100) {
            throw new IllegalArgumentException("invalid lottery parameters");
        }
        synchronized (store) {
            String saved = store.starActions.get("lottery:" + eventId);
            if (saved != null) {
                Map<String, Object> result = Json.obj(Json.parse(saved));
                Map<String, Object> points = Json.obj(result.get("points"));
                return new LotteryResult(
                        new Store.PointResult(Json.lng(points.get("balance"), 0), Json.lng(points.get("earned"), 0)),
                        Json.bool(result.get("won")));
            }
            Store.PointAccount account = store.pointAccounts.get(Store.key(guildId, userId));
            long balance = account == null ? 0 : account.balance;
            if (account == null || balance < cost) {
                throw new IllegalStateException("insufficient points");
            }
            boolean won = random.nextInt(100) < chance;
            long reward = -cost + (won ? prize : 0);
            addLedger("lottery:" + eventId, guildId, userId, reward, won ? "lottery:won" : "lottery:lost");
            account.balance += reward;
            Map<String, Object> resultJson = new LinkedHashMap<>();
            Map<String, Object> pointsJson = new LinkedHashMap<>();
            pointsJson.put("balance", account.balance);
            pointsJson.put("earned", 0L);
            resultJson.put("points", pointsJson);
            resultJson.put("won", won);
            store.starActions.put("lottery:" + eventId, Json.stringify(resultJson));
            afterIdempotentWrite();
            store.save();
            return new LotteryResult(new Store.PointResult(account.balance, 0), won);
        }
    }

    // ===== 4. おみくじ =====
    // Go版 DrawOmikuji: UTC日付ごとに1回。event_id で冪等リプレイ。
    public String drawOmikuji(String guildId, String userId, String eventId) {
        if (guildId.isBlank() || userId.isBlank() || eventId.isBlank()) {
            throw new IllegalArgumentException("guild_id, user_id and event_id are required");
        }
        synchronized (store) {
            String saved = store.starActions.get("omikuji:" + eventId);
            if (saved != null) {
                Map<String, Object> result = Json.obj(Json.parse(saved));
                return Json.str(result.get("fortune"));
            }
            String date = todayUtc();
            String drawKey = Store.key(guildId, userId, date);
            if (store.omikujiDraws.containsKey(drawKey)) {
                throw new IllegalStateException("omikuji already drawn today");
            }
            String fortune = OMIKUJI_RESULTS.get(random.nextInt(OMIKUJI_RESULTS.size()));
            store.omikujiDraws.put(drawKey, fortune);
            addLedger("omikuji:" + eventId, guildId, userId, 0, "omikuji");
            Map<String, Object> resultJson = new LinkedHashMap<>();
            resultJson.put("fortune", fortune);
            store.starActions.put("omikuji:" + eventId, Json.stringify(resultJson));
            pruneOldOmikujiDraws();
            afterIdempotentWrite();
            store.save();
            return fortune;
        }
    }

    // ===== 5. ショップ =====
    public void upsertShopItem(String guildId, Store.ShopItem item) {
        if (guildId.isBlank() || item.itemId.isBlank() || item.itemId.length() > 50
                || item.name.isBlank() || item.name.codePointCount(0, item.name.length()) > 100
                || item.price < 1 || item.roleId.isBlank()) {
            throw new IllegalArgumentException("shop item requires item_id, name, positive price and role_id");
        }
        for (char ch : item.itemId.toCharArray()) {
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-';
            if (!ok) throw new IllegalArgumentException("shop item_id may contain only ASCII letters, numbers and hyphens");
        }
        synchronized (store) {
            Store.ShopItem copy = new Store.ShopItem();
            copy.itemId = item.itemId;
            copy.name = item.name;
            copy.price = item.price;
            copy.roleId = item.roleId;
            copy.enabled = true; // Go版 UpsertShopItem は再登録で enabled=1 に戻す
            store.shopItems.computeIfAbsent(guildId, g -> new LinkedHashMap<>()).put(copy.itemId, copy);
            store.save();
        }
    }

    public List<Store.ShopItem> listShopItems(String guildId) {
        synchronized (store) {
            List<Store.ShopItem> result = new ArrayList<>();
            Map<String, Store.ShopItem> items = store.shopItems.get(guildId);
            if (items != null) {
                for (Store.ShopItem item : items.values()) if (item.enabled) result.add(item);
            }
            result.sort(Comparator.comparing(i -> i.itemId));
            return result;
        }
    }

    // ----- 5.1 購入処理 -----
    // Go版 BuyShopItem: 購入時は role_status=pending で記録し、課金は1回だけ。
    // pending のまま再試行した場合は追加課金なしで付与再試行を返す。
    public BuyResult buyShopItem(String guildId, String userId, String itemId, String eventId) {
        if (guildId.isBlank() || userId.isBlank() || itemId.isBlank() || eventId.isBlank()) {
            throw new IllegalArgumentException("guild_id, user_id, item_id and event_id are required");
        }
        synchronized (store) {
            String saved = store.starActions.get("shop:" + eventId);
            if (saved != null) {
                Map<String, Object> result = Json.obj(Json.parse(saved));
                Map<String, Object> itemJson = Json.obj(result.get("item"));
                Map<String, Object> pointsJson = Json.obj(result.get("points"));
                Store.ShopItem item = new Store.ShopItem();
                item.itemId = Json.str(itemJson.get("item_id"));
                item.name = Json.str(itemJson.get("name"));
                item.price = Json.lng(itemJson.get("price"), 0);
                item.roleId = Json.str(itemJson.get("role_id"));
                item.enabled = true;
                return new BuyResult(item,
                        new Store.PointResult(Json.lng(pointsJson.get("balance"), 0), Json.lng(pointsJson.get("earned"), 0)),
                        Json.bool(result.get("role_pending")));
            }
            Map<String, Store.ShopItem> items = store.shopItems.get(guildId);
            Store.ShopItem item = items == null ? null : items.get(itemId);
            if (item == null) throw new IllegalStateException("shop item not found");
            Store.PointAccount account = store.pointAccounts.get(Store.key(guildId, userId));
            long balance = account == null ? 0 : account.balance;
            Store.InventoryEntry existing = store.inventory.get(Store.key(guildId, userId, itemId));
            if (existing != null) {
                if (!"pending".equals(existing.roleStatus)) {
                    throw new IllegalStateException("shop item already owned");
                }
                // 購入時点のロールを保持する。カタログ側が後で編集・無効化
                // されても pending の再試行は追加課金なしで継続できる。
                // ただし商品自体がカタログから消えた場合は、上の取得時点で
                // "shop item not found" となり再試行できない。
                Store.ShopItem purchased = new Store.ShopItem();
                purchased.itemId = item.itemId;
                purchased.name = item.name;
                purchased.price = item.price;
                purchased.roleId = existing.roleId.isBlank() ? item.roleId : existing.roleId;
                purchased.enabled = item.enabled;
                return new BuyResult(purchased, new Store.PointResult(balance, 0), true);
            }
            if (!item.enabled) throw new IllegalStateException("shop item is disabled");
            if (balance < item.price) throw new IllegalStateException("insufficient points");
            Store.InventoryEntry inv = new Store.InventoryEntry();
            inv.guildId = guildId;
            inv.userId = userId;
            inv.itemId = itemId;
            inv.roleStatus = "pending";
            inv.roleId = item.roleId;
            inv.purchasedAt = now().toString();
            store.inventory.put(Store.key(guildId, userId, itemId), inv);
            addLedger("shop:" + eventId, guildId, userId, -item.price, "shop purchase: " + itemId);
            if (account == null) {
                account = new Store.PointAccount();
                store.pointAccounts.put(Store.key(guildId, userId), account);
            }
            account.balance -= item.price;
            Map<String, Object> resultJson = new LinkedHashMap<>();
            Map<String, Object> itemJson = new LinkedHashMap<>();
            itemJson.put("item_id", item.itemId);
            itemJson.put("name", item.name);
            itemJson.put("price", item.price);
            itemJson.put("role_id", item.roleId);
            resultJson.put("item", itemJson);
            Map<String, Object> pointsJson = new LinkedHashMap<>();
            pointsJson.put("balance", account.balance);
            pointsJson.put("earned", 0L);
            resultJson.put("points", pointsJson);
            resultJson.put("role_pending", true);
            store.starActions.put("shop:" + eventId, Json.stringify(resultJson));
            afterIdempotentWrite();
            store.save();
            Store.ShopItem purchased = new Store.ShopItem();
            purchased.itemId = item.itemId;
            purchased.name = item.name;
            purchased.price = item.price;
            purchased.roleId = item.roleId;
            purchased.enabled = item.enabled;
            return new BuyResult(purchased, new Store.PointResult(account.balance, 0), true);
        }
    }

    // Go版 CompleteShopRoleGrant: 冪等。pending→fulfilled の遷移と
    // 既に fulfilled の場合の再呼び出しはどちらも成功とする。
    public void completeShopRoleGrant(String guildId, String userId, String itemId) {
        if (guildId.isBlank() || userId.isBlank() || itemId.isBlank()) {
            throw new IllegalArgumentException("guild_id, user_id and item_id are required");
        }
        synchronized (store) {
            Store.InventoryEntry inv = store.inventory.get(Store.key(guildId, userId, itemId));
            if (inv == null) throw new IllegalStateException("shop inventory not found");
            if ("pending".equals(inv.roleStatus)) {
                inv.roleStatus = "fulfilled";
                store.save();
                return;
            }
            if (!"fulfilled".equals(inv.roleStatus)) {
                throw new IllegalStateException("shop inventory is not pending role fulfillment");
            }
        }
    }

    public Store.InventoryEntry getInventory(String guildId, String userId, String itemId) {
        synchronized (store) {
            return store.inventory.get(Store.key(guildId, userId, itemId));
        }
    }
}
