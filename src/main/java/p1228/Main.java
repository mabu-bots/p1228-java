package p1228;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

// ===== 1. Main (CLI エントリポイント) =====
// サブコマンド: run / sync-commands / commands / demo / test(案内)
// テスト実体は run.sh test → p1228.P1228Test が担う。
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        Path configPath = Path.of(System.getProperty("p1228.config", "config.json"));
        switch (command) {
            case "commands" -> printCommands(configPath);
            case "demo" -> runDemo();
            case "sync-commands" -> syncCommands(configPath);
            case "run" -> run(configPath);
            case "voice-test" -> runVoiceTest(configPath, args);
            case "test" -> System.out.println("テストは ./run.sh test で実行してください (p1228.P1228Test)。");
            default -> printHelp();
        }
    }

    private static void printHelp() {
        System.out.println("""
                p1228-java — Discord BOT 3体 (星燈/言ノ葉/紡言) の Java 21 移植版
                使い方:
                  ./run.sh test            オフライン単体テストを実行
                  ./run.sh commands        BOTごとの Slash コマンド定義JSONを表示 (オフライン)
                  ./run.sh demo            フェイクDiscordで星燈機能を一通り動かす (オフライン)
                  ./run.sh sync-commands   Discordへ Slash コマンドを同期 (要Token・要ネットワーク)
                  ./run.sh run             3体の Gateway 接続を開始 (要Token・要ネットワーク)
                  ./run.sh voice-test [guild_id channel_id]
                                           引数なし: 言ノ葉が参加中のギルドとVC一覧を表示
                                           引数あり: 指定VCへ接続し短いテスト音声を1回流して退出
                設定: config.json + .env (Token / Application ID / Worker Secret)
                """);
    }

    // ===== 2. commands (オフライン: 定義JSONの表示) =====
    private static void printCommands(Path configPath) throws Exception {
        Config config = Config.load(configPath, false);
        for (String botId : Config.REQUIRED_BOTS) {
            Config.Bot bot = config.bots.get(botId);
            System.out.println("===== bot: " + botId + " (" + bot.displayName + " / watching: " + bot.watching
                    + " / intents: " + bot.intents + ") =====");
            System.out.println(Json.stringify(Commands.forBot(botId)));
            System.out.println();
        }
    }

    // ===== 3. sync-commands (Discord REST 同期) =====
    private static void syncCommands(Path configPath) throws Exception {
        Config config = Config.load(configPath, true);
        for (String botId : Config.REQUIRED_BOTS) {
            Config.Bot bot = config.bots.get(botId);
            DiscordRest rest = new DiscordRest(botId, bot.applicationId, bot.token);
            List<Map<String, Object>> commands = Commands.forBot(botId);
            rest.syncCommands(bot.applicationId, commands, "sync-" + botId + "-" + System.currentTimeMillis());
            System.out.println("application_commands_synced bot_id=" + botId + " count=" + commands.size());
        }
    }

    // ===== 4. run (3体の Gateway 接続) =====
    private static void run(Path configPath) throws Exception {
        Config config = Config.load(configPath, true);
        Path dataPath = Path.of(config.databasePath);
        Store store = new Store(dataPath);
        StarFeatures features = new StarFeatures(store, Clock.systemUTC(), new SecureRandom());
        List<GatewayClient> clients = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            clients.forEach(GatewayClient::stop);
            latch.countDown();
        }));

        for (String botId : Config.REQUIRED_BOTS) {
            Config.Bot bot = config.bots.get(botId);
            DiscordRest rest = new DiscordRest(botId, bot.applicationId, bot.token);
            if ("general".equals(botId)) {
                StarHandler handler = new StarHandler(store, features, rest, new AiClient(config.llamaBaseUrl));
                GatewayClient client = new GatewayClient(bot, event -> {
                    try {
                        handler.handleEvent(new StarHandler.Event(event.eventId(), event.eventType(), event.payload()));
                    } catch (Exception e) {
                        System.err.println("[general] event handling failed: " + e.getMessage());
                    }
                }, System.out::println);
                clients.add(client);
            } else {
                DiscordVoiceTransport transport = new DiscordVoiceTransport(System.out::println);
                ReadAloud.ReadAloudService service = new ReadAloud.ReadAloudService(
                        store, new ReadAloud.VoiceVoxClient(config.voicevoxBaseUrl),
                        transport, System.out::println, true);
                GatewayClient client = new GatewayClient(bot, event -> handleReadAloudEvent(service, rest, transport, event),
                        System.out::println);
                transport.bindGateway(new DiscordVoiceTransport.MainGateway() {
                    @Override
                    public void updateVoiceState(String guildId, String channelId) {
                        client.updateVoiceState(guildId, channelId);
                    }

                    @Override
                    public String selfUserId() {
                        return client.botUserId();
                    }
                });
                clients.add(client);
            }
        }
        clients.forEach(GatewayClient::start);
        System.out.println("p1228-java started: 3 bots connecting");
        latch.await();
    }

    private static void handleReadAloudEvent(ReadAloud.ReadAloudService service, DiscordRest rest,
                                             DiscordVoiceTransport transport,
                                             GatewayClient.GatewayEvent event) {
        try {
            if (event.eventType().equals("INTERACTION_CREATE")) {
                StarHandler.Interaction interaction = StarHandler.Interaction.fromPayload(event.payload());
                if (interaction.type != 2) return;
                try {
                    executeReadAloudCommand(service, rest, interaction);
                    ExecLog.log(interaction.commandName, interaction.guildId, interaction.channelId,
                            interaction.userId, null);
                } catch (Exception e) {
                    ExecLog.log(interaction.commandName, interaction.guildId, interaction.channelId,
                            interaction.userId, e.getMessage());
                    throw e;
                }
            } else if (event.eventType().equals("MESSAGE_CREATE")) {
                Map<String, Object> message = event.payload();
                Map<String, Object> author = Json.obj(message.get("author"));
                if (Json.bool(author.get("bot"))) return;
                service.maybeReadMessage(Json.str(message.get("guild_id")), Json.str(author.get("id")),
                        Json.str(message.get("content")));
            } else if (event.eventType().equals("VOICE_STATE_UPDATE")) {
                transport.onVoiceStateUpdate(event.payload());
            } else if (event.eventType().equals("VOICE_SERVER_UPDATE")) {
                transport.onVoiceServerUpdate(event.payload());
            }
            // VOICE_STATE_UPDATE / VOICE_SERVER_UPDATE は Voice 実転送の
            // 資格情報として DiscordVoiceTransport へ渡す。
        } catch (Exception e) {
            System.err.println("[read-aloud] event handling failed: " + e.getMessage());
        }
    }

    // 読み上げ系 Bot のスラッシュコマンド実行。読み上げ対象のテキスト
    // (say の文章など) はここでは記録しない (実行ログは ExecLog 側で
    // コマンド名・ID・成否のみを出す)。
    private static void executeReadAloudCommand(ReadAloud.ReadAloudService service, DiscordRest rest,
                                                StarHandler.Interaction interaction) throws Exception {
        switch (interaction.commandName) {
            case "join" -> {
                String channelId = interaction.optionString("channel");
                if (channelId.isBlank()) {
                    rest.reply(interaction, "接続先ボイスチャンネルを指定してください。");
                    return;
                }
                service.join(interaction.guildId, channelId);
                rest.reply(interaction, "ボイスチャンネルへの接続要求を送信しました。");
            }
            case "leave" -> {
                service.leave(interaction.guildId);
                rest.reply(interaction, "ボイスチャンネルから退出しました。");
            }
            case "say" -> {
                String text = interaction.optionString("text");
                if (text.isBlank()) {
                    rest.reply(interaction, "読み上げる文章を入力してください。");
                    return;
                }
                service.speak(interaction.guildId, text, interaction.userId);
                rest.reply(interaction, "読み上げキューに追加しました。");
            }
            default -> rest.reply(interaction, "未実装のコマンドです: " + interaction.commandName);
        }
    }

    // ===== voice-test (Voice 実転送の無人確認) =====
    // 引数なし: 言ノ葉 (read_aloud_a) が参加しているギルドと VC を
    // ID と名前だけで一覧する。引数あり: 指定 VC へ接続し、短いテスト
    // 音声を1回だけ流して退出する。トークン等の秘密情報は出力しない。
    private static void runVoiceTest(Path configPath, String[] args) throws Exception {
        Config config = Config.load(configPath, true);
        Config.Bot bot = config.bots.get("read_aloud_a");
        DiscordRest rest = new DiscordRest("read_aloud_a", bot.applicationId, bot.token);
        DiscordVoiceTransport transport = new DiscordVoiceTransport(System.out::println);
        List<Map<String, Object>> guildPayloads = new ArrayList<>();
        GatewayClient client = new GatewayClient(bot, event -> {
            if (event.eventType().equals("GUILD_CREATE")) {
                synchronized (guildPayloads) {
                    guildPayloads.add(event.payload());
                }
            } else if (event.eventType().equals("VOICE_STATE_UPDATE")) {
                transport.onVoiceStateUpdate(event.payload());
            } else if (event.eventType().equals("VOICE_SERVER_UPDATE")) {
                transport.onVoiceServerUpdate(event.payload());
            }
        }, System.out::println);
        transport.bindGateway(new DiscordVoiceTransport.MainGateway() {
            @Override
            public void updateVoiceState(String guildId, String channelId) {
                client.updateVoiceState(guildId, channelId);
            }

            @Override
            public String selfUserId() {
                return client.botUserId();
            }
        });
        client.start();
        try {
            long deadline = System.currentTimeMillis() + 20_000;
            while (client.botUserId().isBlank() && System.currentTimeMillis() < deadline) Thread.sleep(100);
            if (client.botUserId().isBlank()) {
                System.out.println("voice-test: READY not received (bot user id unknown)");
                return;
            }
            Thread.sleep(2000); // GUILD_CREATE の到着を少し待つ
            if (args.length < 3) {
                synchronized (guildPayloads) {
                    for (Map<String, Object> guild : guildPayloads) {
                        System.out.println("guild " + Json.str(guild.get("id")) + " " + Json.str(guild.get("name")));
                        for (Object o : Json.arr(guild.get("channels"))) {
                            Map<String, Object> channel = Json.obj(o);
                            long type = Json.lng(channel.get("type"), -1);
                            if (type == 2 || type == 13) {
                                System.out.println("  voice " + Json.str(channel.get("id")) + " " + Json.str(channel.get("name")));
                            }
                        }
                    }
                }
                return;
            }
            String guildId = args[1];
            String channelId = args[2];
            transport.join(guildId, channelId);
            long connectDeadline = System.currentTimeMillis() + 20_000;
            while (!transport.isConnected(guildId) && System.currentTimeMillis() < connectDeadline) Thread.sleep(100);
            if (!transport.isConnected(guildId)) {
                System.out.println("voice-test: connection not ready guild=" + guildId + " channel=" + channelId);
                transport.leave(guildId);
                return;
            }
            System.out.println("voice-test: connected guild=" + guildId + " channel=" + channelId
                    + " dave_version=" + transport.negotiatedDaveVersion(guildId));
            byte[] wav;
            try {
                ReadAloud.Settings settings = new ReadAloud.Settings();
                wav = new ReadAloud.VoiceVoxClient(config.voicevoxBaseUrl).synthesize("音声テストです。", settings);
                System.out.println("voice-test: synthesized by VOICEVOX (" + wav.length + " bytes)");
            } catch (Exception voicevoxError) {
                wav = toneWav();
                System.out.println("voice-test: VOICEVOX unavailable (" + voicevoxError.getMessage() + "); using tone");
            }
            transport.play(guildId, wav);
            System.out.println("voice-test: playback finished");
            transport.leave(guildId);
            Thread.sleep(500);
        } finally {
            client.stop();
        }
    }

    // VOICEVOX が使えない場合のフォールバック: 24kHz mono 1秒の正弦波 WAV。
    private static byte[] toneWav() {
        int rate = 24_000;
        int samples = rate;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            int dataSize = samples * 2;
            out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(36 + dataSize).array());
            out.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(16).array());
            java.nio.ByteBuffer fmt = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            fmt.putShort((short) 1);
            fmt.putShort((short) 1);
            fmt.putInt(rate);
            fmt.putInt(rate * 2);
            fmt.putShort((short) 2);
            fmt.putShort((short) 16);
            out.write(fmt.array());
            out.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(dataSize).array());
            java.nio.ByteBuffer pcm = java.nio.ByteBuffer.allocate(dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < samples; i++) {
                double envelope = Math.min(1.0, Math.min(i, samples - i) / 2000.0);
                pcm.putShort((short) (Math.sin(2 * Math.PI * 440 * i / (double) rate) * 12000 * envelope));
            }
            out.write(pcm.array());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    // ===== 5. demo (オフライン模擬実行) =====
    // ----- フェイク DiscordOps -----
    static final class FakeOps implements StarHandler.DiscordOps {
        final List<String> actions = new ArrayList<>();
        private int messageCounter = 0;

        @Override
        public String sendMessage(String channelId, String content, Object components, String operationId) {
            String id = "demo-msg-" + (++messageCounter);
            actions.add("SEND channel=" + channelId + " id=" + id + " content=" + content
                    + (components != null ? " components=" + Json.stringify(components) : ""));
            return id;
        }

        @Override
        public void deleteMessage(String channelId, String messageId, String operationId) {
            actions.add("DELETE channel=" + channelId + " message=" + messageId);
        }

        @Override
        public void addRole(String guildId, String userId, String roleId, String operationId) {
            actions.add("ADD_ROLE guild=" + guildId + " user=" + userId + " role=" + roleId);
        }

        @Override
        public void removeRole(String guildId, String userId, String roleId, String operationId) {
            actions.add("REMOVE_ROLE guild=" + guildId + " user=" + userId + " role=" + roleId);
        }

        @Override
        public String createVoiceChannel(String guildId, String name, String parentId, String operationId) {
            String id = "demo-temp-vc-" + (++messageCounter);
            actions.add("CREATE_VC guild=" + guildId + " id=" + id + " name=" + name + " parent=" + parentId);
            return id;
        }

        @Override
        public void deleteChannel(String channelId, String operationId) {
            actions.add("DELETE_CHANNEL channel=" + channelId);
        }

        @Override
        public void moveMember(String guildId, String userId, String channelId, String operationId) {
            actions.add("MOVE_MEMBER guild=" + guildId + " user=" + userId + " channel=" + channelId);
        }

        @Override
        public void reply(StarHandler.Interaction interaction, String content) {
            actions.add("REPLY command=" + interaction.commandName + " content=" + content);
        }

        @Override
        public void ackComponent(StarHandler.Interaction interaction) {
            actions.add("ACK_COMPONENT custom_id=" + interaction.customId);
        }

        @Override
        public void followup(StarHandler.Interaction interaction, String content) {
            actions.add("FOLLOWUP content=" + content);
        }

        @Override
        public void deferReply(StarHandler.Interaction interaction) {
            actions.add("DEFER_REPLY command=" + interaction.commandName);
        }

        @Override
        public void editOriginal(StarHandler.Interaction interaction, String content) {
            actions.add("EDIT_ORIGINAL command=" + interaction.commandName + " content=" + content);
        }

        void printAll() {
            actions.forEach(a -> System.out.println("  " + a));
        }
    }

    private static Map<String, Object> interactionPayload(String id, String name, String subcommand,
                                                          Map<String, ?> options, String permissions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("application_id", "demo-app");
        payload.put("type", 2L);
        payload.put("token", "demo-token");
        payload.put("guild_id", "g1");
        payload.put("channel_id", "ch-general");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u-admin");
        user.put("username", "admin");
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("user", user);
        member.put("permissions", permissions);
        payload.put("member", member);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        List<Object> opts = new ArrayList<>();
        if (subcommand != null) {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("name", subcommand);
            sub.put("type", 1L);
            List<Object> subOpts = new ArrayList<>();
            options.forEach((k, v) -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", k);
                o.put("type", v instanceof Number ? 4L : 3L);
                o.put("value", v);
                subOpts.add(o);
            });
            sub.put("options", subOpts);
            opts.add(sub);
        } else {
            options.forEach((k, v) -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", k);
                o.put("type", v instanceof Number ? 4L : 3L);
                o.put("value", v);
                opts.add(o);
            });
        }
        data.put("options", opts);
        payload.put("data", data);
        return payload;
    }

    private static void runDemo() throws Exception {
        System.out.println("===== p1228-java demo (オフライン模擬実行) =====");
        Path dir = Files.createTempDirectory("p1228-demo");
        Store store = new Store(dir.resolve("store.json"));
        Clock fixed = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
        StarFeatures features = new StarFeatures(store, fixed, new Random(42));
        FakeOps ops = new FakeOps();
        StarHandler handler = new StarHandler(store, features, ops,
                prompt -> "（demo）これはAI会話の固定応答です。実際のLLMには接続していません。");
        String admin = "8"; // ADMINISTRATOR

        System.out.println("-- 1. GUILD_CREATE (サーバー: デモサーバー) --");
        Map<String, Object> guild = new LinkedHashMap<>();
        guild.put("id", "g1");
        guild.put("name", "デモサーバー");
        Map<String, Object> vc = new LinkedHashMap<>();
        vc.put("id", "vc1");
        vc.put("type", 2L);
        vc.put("parent_id", "cat1");
        guild.put("channels", List.of(vc));
        handler.handleEvent(new StarHandler.Event("ev-guild", "GUILD_CREATE", guild));

        System.out.println("-- 2. 管理コマンド: welcome / autorole / logs / sticky / shop add --");
        handler.handleEvent(new StarHandler.Event("i1", "INTERACTION_CREATE",
                interactionPayload("i1", "welcome", "set", Map.of("channel", "ch-welcome", "message", "{user} さん、{server} へようこそ！"), admin)));
        handler.handleEvent(new StarHandler.Event("i2", "INTERACTION_CREATE",
                interactionPayload("i2", "autorole", "set", Map.of("role", "role-member"), admin)));
        handler.handleEvent(new StarHandler.Event("i3", "INTERACTION_CREATE",
                interactionPayload("i3", "logs", "set", Map.of("event", "voice", "channel", "ch-log"), admin)));
        handler.handleEvent(new StarHandler.Event("i4", "INTERACTION_CREATE",
                interactionPayload("i4", "sticky", "set", Map.of("channel", "ch-general", "message", "📌 固定メッセージです"), admin)));
        handler.handleEvent(new StarHandler.Event("i5", "INTERACTION_CREATE",
                interactionPayload("i5", "shop", "add", Map.of("item_id", "vip", "name", "VIPロール", "price", 30L, "role", "role-vip"), admin)));

        System.out.println("-- 3. メンバー参加 (welcome + autorole) --");
        Map<String, Object> join = new LinkedHashMap<>();
        join.put("guild_id", "g1");
        Map<String, Object> joinUser = new LinkedHashMap<>();
        joinUser.put("id", "u1");
        joinUser.put("username", "taro");
        join.put("user", joinUser);
        join.put("roles", List.of());
        handler.handleEvent(new StarHandler.Event("ev-join", "GUILD_MEMBER_ADD", join));

        System.out.println("-- 4. メッセージ投稿 x2 (ポイント獲得 + sticky再投稿) --");
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("id", "m" + i);
            msg.put("guild_id", "g1");
            msg.put("channel_id", "ch-general");
            Map<String, Object> author = new LinkedHashMap<>();
            author.put("id", "u1");
            author.put("username", "taro");
            msg.put("author", author);
            msg.put("content", "こんにちは " + i);
            handler.handleEvent(new StarHandler.Event("ev-msg-" + i, "MESSAGE_CREATE", msg));
        }
        System.out.println("  ポイント残高 (u1): " + features.getPointAccount("g1", "u1").balance() + " pt (クールダウン60秒のため2件目は0pt)");

        System.out.println("-- 5. 一般コマンド: points / omikuji / shop list --");
        Map<String, Object> userPayload = interactionPayload("i6", "points", null, Map.of(), "0");
        Json.obj(userPayload.get("member")).put("user", Map.of("id", "u1", "username", "taro"));
        handler.handleEvent(new StarHandler.Event("i6", "INTERACTION_CREATE", userPayload));
        Map<String, Object> omikujiPayload = interactionPayload("i7", "omikuji", null, Map.of(), "0");
        Json.obj(omikujiPayload.get("member")).put("user", Map.of("id", "u1", "username", "taro"));
        handler.handleEvent(new StarHandler.Event("i7", "INTERACTION_CREATE", omikujiPayload));
        handler.handleEvent(new StarHandler.Event("i8", "INTERACTION_CREATE",
                interactionPayload("i8", "shop", "list", Map.of(), "0")));

        System.out.println("-- 6. VC参加ログ --");
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("guild_id", "g1");
        voice.put("user_id", "u1");
        voice.put("channel_id", "vc1");
        handler.handleEvent(new StarHandler.Event("ev-voice", "VOICE_STATE_UPDATE", voice));

        System.out.println("-- 7. 読み上げ (LogVoiceTransport + フェイク合成) --");
        ReadAloud.ReadAloudService service = new ReadAloud.ReadAloudService(store,
                (text, settings) -> new byte[44],
                new ReadAloud.LogVoiceTransport(System.out::println), System.out::println, false);
        store.putReadAloudSettings("global", "global", Map.<String, Object>of("enabled", true));
        service.join("g1", "vc1");
        service.speak("g1", "テスト読み上げです https://example.com は省略されます", "u1");
        service.leave("g1");

        System.out.println();
        System.out.println("===== DiscordOps へ送られた操作 (" + ops.actions.size() + "件) =====");
        ops.printAll();
        System.out.println("===== demo 終了: すべてオフラインで実行されました =====");
    }
}
