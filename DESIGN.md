# p1228 Java版 設計書 v1.0 — Discord BOT 3体の単一JVM移植

作成: 2026-11-17 / 原本: i-mabu/p1228 (Go Gateway + TypeScript Workers)

## 1. 位置づけ

原本 p1228 と同一仕様を **Java 21 / 標準ライブラリのみ** で再実装したもの。
外部依存ゼロ (Maven/Gradle 不要、`javac` だけでビルドできる) を守るため、
原本の構成要素を以下のように置き換えた。aegis-java の家内様式
(ビルドスクリプト、Json.java、ProxySupport、ファイルベース Store、
正直な制約の明記) を踏襲する。

## 2. 対応表 (原本 → Java版)

| 原本                                                        | Java版                                                   | 備考                                                                                     |
| ----------------------------------------------------------- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| Go Gateway (プロセス) + TS Worker ×3 (プロセス)             | 単一JVM・単一プロセス (`Main.run`)                       | BOT 3体の役割とコード境界は維持                                                          |
| `gateway/internal/config/config.go` + `config/gateway.yaml` | `Config.java` + `config.json`                            | 検証規則を移植。`${ENV}` 展開、既定値、strict/lenient の2モード                          |
| `gateway/internal/discord/gateway.go`                       | `GatewayClient.java`                                     | `java.net.http.WebSocket`。Hello/Heartbeat/Identify/Resume/再接続                        |
| Gateway の DiscordOperation (内部API経由)                   | `DiscordRest.java`                                       | 許可リスト6操作は同一。`operation_id` 冪等キャッシュも維持                               |
| Interaction 応答 (`interaction_ref` 経由)                   | Discord Interaction Callback / Webhook へ直接REST        | 単一JVMでは中継が不要なための置換                                                        |
| SQLite (`modernc.org/sqlite`、マイグレーション4本)          | ファイルベース `Store.java` (`data/store.json`)          | 標準ライブラリに SQLite ドライバがないための置換。全変更を synchronized + アトミック書換 |
| `gateway/internal/storage/star_features.go`                 | `StarFeatures.java`                                      | 意味論を忠実に移植。Clock / Random 注入で決定的テスト可能に                              |
| `workers/src/general/star-features.ts`                      | `StarHandler.java` + `Commands.java`                     | GatewayClient 依存を `DiscordOps` インターフェースに置換しフェイクで検証                 |
| `workers/src/sync-commands.ts`                              | `Commands.java` + `Main` の `commands` / `sync-commands` | BOTごとの定義振り分けは同一                                                              |
| `workers/src/read-aloud/index.ts` / `voice/service.ts`      | `ReadAloud.ReadAloudService`                             | join/leave/say、メッセージ読み上げ判定、ギルド別キュー (上限50)                          |
| `workers/src/read-aloud/audio/engine.ts`                    | `ReadAloud.VoiceVoxClient`                               | `/audio_query` → `/synthesis` の2段階は同一。音声キャッシュは未実装                      |
| `@discordjs/voice` (UDP/Opus/DAVE)                          | `ReadAloud.VoiceTransport` 境界 + `LogVoiceTransport`    | **実転送は未実装** (標準ライブラリ対象外)。原本も E2E 未検証                             |
| Worker Heartbeat / Presence 障害表示                        | 未実装                                                   | 単一プロセスでは Worker 障害の概念がないため。Gateway 切断時は再接続バックオフのみ       |
| イベント Inbox / リース / ACK / Dead Letter                 | 未実装                                                   | プロセス内直接配送に置換。冪等性は台帳・star_feature_actions 相当の記録で担保            |

## 3. ゲーム・ショップ意味論の移植ポイント

- ポイント獲得: 台帳キー `earn:<eventId>` で重複排除、ユーザーごとクールダウン、UTC日次上限は到達分で切り捨て (Go版 `EarnPoints` と同一)。
- 抽選: `star_feature_actions` 相当の記録キー `lottery:<eventId>` で冪等リプレイ。当選判定は `0..99 < chance` (Go版は crypto/rand、Java版は注入された `Random`/`SecureRandom`)。
- おみくじ: 結果6種、UTC日付ごとに1回 (`omikuji already drawn today`)、`omikuji:<eventId>` で冪等リプレイ。
- ショップ: `item_id` は `[A-Za-z0-9-]`・50字まで、名前100字まで、価格は正数。購入は `role_status=pending` で記録し課金は1回だけ。pending の再試行は購入時点のロールIDを使い追加課金なし。`completeShopRoleGrant` は冪等。
- エラー文字列 (`insufficient points` 等) は Go版と一致させ、Worker側の既知エラー変換 (日本語メッセージ化) も `StarHandler` に移植した。

## 4. 読み上げの移植ポイント

- 設定マージ: Global → Guild → User。下位スコープに無いキーは上書きしない (原本は revision>0 のスナップショットのみ適用。Java版は「そのスコープに設定が存在すれば適用」で等価に扱う)。
- 辞書: スコープ統合は後勝ち (global < guild < user)、適用は単語長の降順 (長い単語を先に置換)。
- テキスト整形: URL・メンション・チャンネル参照・カスタム絵文字の簡略化は Java版独自の簡略実装 (README に明記)。
- キュー: ギルド別、1件の失敗で後続を止めない、セッション喪失時は打ち切る、という原本の方針を維持。

## 5. 設定と検証

- `run` / `sync-commands` は strict: Token・Application ID の実在と相異、Worker Secret の実在・32文字以上・相異を必須とする (Go版 `Load` と同一)。
- `commands` / テストは lenient: 実在チェックを外し、構造・重複・長さの検証のみ行う。
- intents は原本の値を踏襲: general=33411、read-aloud=33409。**33409 に GUILD_MEMBERS ビットが含まれない既知の差異**も原本どおり保持し、README と Config のコメントに明記した。

## 6. 限界

- Discord 実接続 (Gateway/REST/Voice) はこの開発VMでは未検証 (Javaプロセスの直接TCPが権限で遮断されるため)。GatewayClient と DiscordRest はコンパイル済みで構造は完成している。
- Voice 実転送 (UDP/Opus/DAVE) は未実装。`VoiceTransport` を実装すれば差し替え可能な境界にしてある。
- Store は単一JSONファイルのため、原本 SQLite のような高頻度・大規模運用には向かない。書き換えはアトミックだが、プロセスを跨いだ同時書き込みは想定しない。
