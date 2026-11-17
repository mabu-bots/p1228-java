package p1228;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// ===== 1. Store (ファイルベース永続化) =====
// Java標準ライブラリには SQLite ドライバがないため、原本の SQLite を
// 単一JSONファイル (data/store.json) へ置換する (aegis-java と同じ判断)。
// 全変更は synchronized で直列化し、変更のたびに一時ファイル経由で
// アトミックに書き換える。再起動時はファイルから全状態を復元する。
public final class Store {

    // ----- 1.1 型定義 -----
    public static final class StickyMessage {
        public String guildId = "";
        public String channelId = "";
        public String messageId = "";
        public String content = "";
    }

    public static final class ShopItem {
        public String itemId = "";
        public String name = "";
        public long price = 0;
        public String roleId = "";
        public boolean enabled = true;
    }

    public static final class PointAccount {
        public long balance = 0;
        public long earnedToday = 0;
        public String earnedDate = "";
        public String lastEarnedAt = ""; // ISO-8601 Instant 文字列。未獲得なら空。
    }

    public record PointResult(long balance, long earned) {}

    public static final class InventoryEntry {
        public String guildId = "";
        public String userId = "";
        public String itemId = "";
        public String roleStatus = "pending"; // pending | fulfilled
        public String roleId = "";
        public String purchasedAt = "";
    }

    public record DictionaryEntry(String entryId, String word, String reading) {}

    public static final class ReadAloudSettingEntry {
        public Map<String, Object> settings = new LinkedHashMap<>();
        public long revision = 0;
    }

    // ----- 1.2 内部状態 -----
    private final Path file;
    // guild_id -> (key -> JSON値)
    final Map<String, Map<String, Object>> guildSettings = new LinkedHashMap<>();
    // guild_id -> (user_id -> reason)
    final Map<String, Map<String, String>> blacklist = new LinkedHashMap<>();
    // guild|channel -> sticky
    final Map<String, StickyMessage> stickyMessages = new LinkedHashMap<>();
    // guild|user -> account
    final Map<String, PointAccount> pointAccounts = new LinkedHashMap<>();
    // event_key -> ledger entry (冪等判定はキー集合で行う)
    final Map<String, Map<String, Object>> pointLedger = new LinkedHashMap<>();
    // guild -> (item_id -> item)
    final Map<String, Map<String, ShopItem>> shopItems = new LinkedHashMap<>();
    // guild|user|item -> inventory
    final Map<String, InventoryEntry> inventory = new LinkedHashMap<>();
    // guild|user|date(UTC) -> fortune
    final Map<String, String> omikujiDraws = new LinkedHashMap<>();
    // event_key -> result_json
    final Map<String, String> starActions = new LinkedHashMap<>();
    // scope|scope_id -> settings
    final Map<String, ReadAloudSettingEntry> readAloudSettings = new LinkedHashMap<>();
    // scope|scope_id -> entries
    final Map<String, List<DictionaryEntry>> dictionary = new LinkedHashMap<>();

    static String key(String... parts) {
        return String.join("|", parts);
    }

    public Store(Path file) {
        this.file = file;
        load();
    }

    public Path file() {
        return file;
    }

    // ----- 1.3 読み込みと保存 -----
    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Object> root = Json.obj(parsed);
            for (Map.Entry<String, Object> e : Json.obj(root.get("guild_settings")).entrySet()) {
                guildSettings.put(e.getKey(), new LinkedHashMap<>(Json.obj(e.getValue())));
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("blacklist")).entrySet()) {
                Map<String, String> users = new LinkedHashMap<>();
                for (Map.Entry<String, Object> u : Json.obj(e.getValue()).entrySet()) users.put(u.getKey(), Json.str(u.getValue()));
                blacklist.put(e.getKey(), users);
            }
            for (Object o : Json.arr(root.get("sticky_messages"))) {
                Map<String, Object> m = Json.obj(o);
                StickyMessage s = new StickyMessage();
                s.guildId = Json.str(m.get("guild_id"));
                s.channelId = Json.str(m.get("channel_id"));
                s.messageId = Json.str(m.get("message_id"));
                s.content = Json.str(m.get("content"));
                stickyMessages.put(key(s.guildId, s.channelId), s);
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("point_accounts")).entrySet()) {
                Map<String, Object> m = Json.obj(e.getValue());
                PointAccount a = new PointAccount();
                a.balance = Json.lng(m.get("balance"), 0);
                a.earnedToday = Json.lng(m.get("earned_today"), 0);
                a.earnedDate = Json.str(m.get("earned_date"));
                a.lastEarnedAt = Json.str(m.get("last_earned_at"));
                pointAccounts.put(e.getKey(), a);
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("point_ledger")).entrySet()) {
                pointLedger.put(e.getKey(), new LinkedHashMap<>(Json.obj(e.getValue())));
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("shop_items")).entrySet()) {
                Map<String, ShopItem> items = new LinkedHashMap<>();
                for (Map.Entry<String, Object> i : Json.obj(e.getValue()).entrySet()) {
                    Map<String, Object> m = Json.obj(i.getValue());
                    ShopItem item = new ShopItem();
                    item.itemId = Json.str(m.get("item_id"));
                    item.name = Json.str(m.get("name"));
                    item.price = Json.lng(m.get("price"), 0);
                    item.roleId = Json.str(m.get("role_id"));
                    item.enabled = !m.containsKey("enabled") || Json.bool(m.get("enabled"));
                    items.put(item.itemId, item);
                }
                shopItems.put(e.getKey(), items);
            }
            for (Object o : Json.arr(root.get("inventory"))) {
                Map<String, Object> m = Json.obj(o);
                InventoryEntry inv = new InventoryEntry();
                inv.guildId = Json.str(m.get("guild_id"));
                inv.userId = Json.str(m.get("user_id"));
                inv.itemId = Json.str(m.get("item_id"));
                inv.roleStatus = Json.str(m.get("role_status"), "pending");
                inv.roleId = Json.str(m.get("role_id"));
                inv.purchasedAt = Json.str(m.get("purchased_at"));
                inventory.put(key(inv.guildId, inv.userId, inv.itemId), inv);
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("omikuji_draws")).entrySet()) {
                omikujiDraws.put(e.getKey(), Json.str(e.getValue()));
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("star_feature_actions")).entrySet()) {
                starActions.put(e.getKey(), Json.str(e.getValue()));
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("read_aloud_settings")).entrySet()) {
                Map<String, Object> m = Json.obj(e.getValue());
                ReadAloudSettingEntry entry = new ReadAloudSettingEntry();
                entry.settings = new LinkedHashMap<>(Json.obj(m.get("settings")));
                entry.revision = Json.lng(m.get("revision"), 0);
                readAloudSettings.put(e.getKey(), entry);
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("dictionary")).entrySet()) {
                List<DictionaryEntry> entries = new ArrayList<>();
                for (Object o : Json.arr(e.getValue())) {
                    Map<String, Object> m = Json.obj(o);
                    entries.add(new DictionaryEntry(Json.str(m.get("entry_id")), Json.str(m.get("word")), Json.str(m.get("reading"))));
                }
                dictionary.put(e.getKey(), entries);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load store: " + file, ex);
        }
    }

    public synchronized void save() {
        if (file == null) return;
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> gs = new LinkedHashMap<>();
        guildSettings.forEach((g, m) -> gs.put(g, m));
        root.put("guild_settings", gs);
        Map<String, Object> bl = new LinkedHashMap<>();
        blacklist.forEach((g, m) -> bl.put(g, new LinkedHashMap<>(m)));
        root.put("blacklist", bl);
        List<Object> stickies = new ArrayList<>();
        for (StickyMessage s : stickyMessages.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("guild_id", s.guildId);
            m.put("channel_id", s.channelId);
            m.put("message_id", s.messageId);
            m.put("content", s.content);
            stickies.add(m);
        }
        root.put("sticky_messages", stickies);
        Map<String, Object> accounts = new TreeMap<>();
        pointAccounts.forEach((k, a) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("balance", a.balance);
            m.put("earned_today", a.earnedToday);
            m.put("earned_date", a.earnedDate);
            m.put("last_earned_at", a.lastEarnedAt);
            accounts.put(k, m);
        });
        root.put("point_accounts", accounts);
        root.put("point_ledger", new LinkedHashMap<>(pointLedger));
        Map<String, Object> shops = new LinkedHashMap<>();
        shopItems.forEach((g, items) -> {
            Map<String, Object> byId = new LinkedHashMap<>();
            items.forEach((id, item) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("item_id", item.itemId);
                m.put("name", item.name);
                m.put("price", item.price);
                m.put("role_id", item.roleId);
                m.put("enabled", item.enabled);
                byId.put(id, m);
            });
            shops.put(g, byId);
        });
        root.put("shop_items", shops);
        List<Object> invList = new ArrayList<>();
        for (InventoryEntry inv : inventory.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("guild_id", inv.guildId);
            m.put("user_id", inv.userId);
            m.put("item_id", inv.itemId);
            m.put("role_status", inv.roleStatus);
            m.put("role_id", inv.roleId);
            m.put("purchased_at", inv.purchasedAt);
            invList.add(m);
        }
        root.put("inventory", invList);
        root.put("omikuji_draws", new LinkedHashMap<>(omikujiDraws));
        root.put("star_feature_actions", new LinkedHashMap<>(starActions));
        Map<String, Object> ra = new LinkedHashMap<>();
        readAloudSettings.forEach((k, entry) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("settings", entry.settings);
            m.put("revision", entry.revision);
            ra.put(k, m);
        });
        root.put("read_aloud_settings", ra);
        Map<String, Object> dict = new LinkedHashMap<>();
        dictionary.forEach((k, entries) -> {
            List<Object> list = new ArrayList<>();
            for (DictionaryEntry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("entry_id", e.entryId());
                m.put("word", e.word());
                m.put("reading", e.reading());
                list.add(m);
            }
            dict.put(k, list);
        });
        root.put("dictionary", dict);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save store: " + file, ex);
        }
    }

    // ===== 2. ギルド設定とブラックリスト =====
    public synchronized Map<String, Object> getGuildSettings(String guildId) {
        Map<String, Object> settings = guildSettings.get(guildId);
        return settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
    }

    public synchronized Object getGuildSetting(String guildId, String key) {
        Map<String, Object> settings = guildSettings.get(guildId);
        return settings == null ? null : settings.get(key);
    }

    public synchronized void setGuildSetting(String guildId, String key, Object value) {
        if (guildId == null || guildId.isBlank() || key == null || key.isBlank() || key.length() > 100) {
            throw new IllegalArgumentException("guild_id, setting key and valid JSON value are required");
        }
        Map<String, Object> settings = guildSettings.computeIfAbsent(guildId, g -> new LinkedHashMap<>());
        if (value == null) settings.remove(key);
        else settings.put(key, value);
        save();
    }

    // ----- 2.1 ブラックリスト -----
    public synchronized void addBlacklist(String guildId, String userId, String reason) {
        if (guildId == null || guildId.isBlank() || userId == null || userId.isBlank()
                || (reason != null && reason.codePointCount(0, reason.length()) > 500)) {
            throw new IllegalArgumentException("valid guild_id and user_id are required");
        }
        blacklist.computeIfAbsent(guildId, g -> new LinkedHashMap<>()).put(userId, reason == null ? "" : reason);
        save();
    }

    public synchronized void removeBlacklist(String guildId, String userId) {
        Map<String, String> users = blacklist.get(guildId);
        if (users != null) users.remove(userId);
        save();
    }

    public synchronized boolean isBlacklisted(String guildId, String userId) {
        Map<String, String> users = blacklist.get(guildId);
        return users != null && users.containsKey(userId);
    }

    // ===== 3. スティッキーメッセージ =====
    public synchronized void setStickyMessage(StickyMessage sticky) {
        if (sticky.guildId.isBlank() || sticky.channelId.isBlank() || sticky.content.isBlank()
                || sticky.content.codePointCount(0, sticky.content.length()) > 2000) {
            throw new IllegalArgumentException("guild_id, channel_id and message content are required");
        }
        StickyMessage copy = new StickyMessage();
        copy.guildId = sticky.guildId;
        copy.channelId = sticky.channelId;
        copy.messageId = sticky.messageId;
        copy.content = sticky.content;
        stickyMessages.put(key(copy.guildId, copy.channelId), copy);
        save();
    }

    public synchronized StickyMessage getStickyMessage(String guildId, String channelId) {
        StickyMessage s = stickyMessages.get(key(guildId, channelId));
        if (s == null) return null;
        StickyMessage copy = new StickyMessage();
        copy.guildId = s.guildId;
        copy.channelId = s.channelId;
        copy.messageId = s.messageId;
        copy.content = s.content;
        return copy;
    }

    public synchronized List<StickyMessage> listStickyMessages(String guildId) {
        List<StickyMessage> result = new ArrayList<>();
        for (StickyMessage s : stickyMessages.values()) {
            if (s.guildId.equals(guildId)) result.add(getStickyMessage(s.guildId, s.channelId));
        }
        return result;
    }

    public synchronized void removeStickyMessage(String guildId, String channelId) {
        stickyMessages.remove(key(guildId, channelId));
        save();
    }

    // 現在の message_id が expectedOld と一致する場合だけ差し替える (Go版と同等)。
    public synchronized boolean updateStickyMessageId(String guildId, String channelId, String expectedOld, String newMessageId) {
        StickyMessage s = stickyMessages.get(key(guildId, channelId));
        if (s == null || !s.messageId.equals(expectedOld == null ? "" : expectedOld)) return false;
        s.messageId = newMessageId == null ? "" : newMessageId;
        save();
        return true;
    }

    // ===== 4. 読み上げ設定と辞書 =====
    public synchronized ReadAloudSettingEntry getReadAloudSettings(String scope, String scopeId) {
        ReadAloudSettingEntry entry = readAloudSettings.get(key(scope, scopeId));
        if (entry == null) return null;
        ReadAloudSettingEntry copy = new ReadAloudSettingEntry();
        copy.settings = new LinkedHashMap<>(entry.settings);
        copy.revision = entry.revision;
        return copy;
    }

    public synchronized long putReadAloudSettings(String scope, String scopeId, Map<String, Object> settings) {
        ReadAloudSettingEntry entry = readAloudSettings.computeIfAbsent(key(scope, scopeId), k -> new ReadAloudSettingEntry());
        entry.settings = new LinkedHashMap<>(settings);
        entry.revision += 1;
        save();
        return entry.revision;
    }

    public synchronized List<DictionaryEntry> getDictionary(String scope, String scopeId) {
        return new ArrayList<>(dictionary.getOrDefault(key(scope, scopeId), List.of()));
    }

    public synchronized void upsertDictionaryEntry(String scope, String scopeId, DictionaryEntry entry) {
        List<DictionaryEntry> entries = dictionary.computeIfAbsent(key(scope, scopeId), k -> new ArrayList<>());
        entries.removeIf(e -> e.word().equals(entry.word()));
        entries.add(entry);
        save();
    }

    // ===== 5. ショップ補助 (テスト・運用向け) =====
    public synchronized void setShopItemEnabled(String guildId, String itemId, boolean enabled) {
        Map<String, ShopItem> items = shopItems.get(guildId);
        if (items != null && items.containsKey(itemId)) {
            items.get(itemId).enabled = enabled;
            save();
        }
    }
}
