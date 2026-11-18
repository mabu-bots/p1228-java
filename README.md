# 🌌 p1228 (Java版) — Discord BOT Gateway & Workers の単一JVM移植

原本 [i-mabu/p1228](https://github.com/i-mabu/p1228) (Go Gateway + TypeScript Workers) と同一仕様の **Java 21 移植版**です。3体の Discord BOT を1つの Java プロセスで運用します。

- 言語: **Java 21 / 標準ライブラリのみ（外部依存ゼロ・Maven/Gradle不要）**
- 構成: 原本の Gateway / Worker 分離を単一JVMに統合しつつ、3体の役割はそのまま維持
- 永続化: SQLite の代わりにファイルベース Store (`data/store.json`) — Java標準ライブラリに SQLite ドライバがないための置換 (詳細は [DESIGN.md](./DESIGN.md))

> [!WARNING]
> **本番運用前の注意:** 原本と同様、Discord実接続、権限、Slash Command、Voice/DAVE の実環境検証は別途必要です。本リポジトリの検証はすべてオフラインで実施しています。

## ✨ BOT構成

| BOT    | ID             | 主な役割                      | Watching表示       | Intents |
| ------ | -------------- | ----------------------------- | ------------------ | ------- |
| 星燈   | `general`      | サーバー機能・管理コマンド    | サーバーを見守り中 | 33411   |
| 言ノ葉 | `read_aloud_a` | 読み上げ・独立Voiceセッション | 言葉を紡ぎ中       | 33409   |
| 紡言   | `read_aloud_b` | 読み上げ・独立Voiceセッション | 声をお届け中       | 33409   |

> [!NOTE]
> 既知の差異: read-aloud 2体の intents `33409` には GUILD_MEMBERS ビットが含まれていません (原本 `config/gateway.yaml` のコメントは含むと書かれていますが値が一致しない、原本でも未解決の要確認事項)。Java版は原本の値をそのまま踏襲しています。

## 🧩 主な機能

### 🌟 星燈（General）

- 参加／退出メッセージ (`{user}` `{user_id}` `{username}` `{server}` プレースホルダ、2000字上限)、自動ロール、ブラックリスト
- イベント別・VC別ログ (優先順位: チャンネル別 > カテゴリー別 > 共通)、固定メッセージ (スティッキー)、ロールパネル (最大5ボタン)
- おみくじ (UTC日付ごとに1回)、ポイント (初期値: 1pt/メッセージ、60秒クールダウン、UTC日次上限50pt)、抽選 (10pt / 当選確率20% / 当選時追加50pt)、ロールショップ (購入時は pending で記録し、ロール付与の再試行で二重課金しない)

管理コマンド: `/ping`、`/welcome`、`/goodbye`、`/autorole`、`/blacklist`、`/logs`、`/sticky`、`/role-panel`、`/game-config`、`/shop`

一般ユーザー向け: `/omikuji`、`/points`、`/lottery`、`/buy`

### 🔊 言ノ葉・紡言（Read Aloud）

- `/join`、`/leave`、`/say`
- Global → Guild → User の順で設定をマージ (無いキーは上書きしない)、辞書は長い単語を先に置換
- VOICEVOX ENGINE 互換 HTTP API (`/audio_query` → `/synthesis`) で音声合成、ギルド別キュー (上限50件)
- **実Voice転送 (UDP/Opus/DAVE) は未実装** — 下記「正直な制約」参照

## 💻 必要なもの

- JDK 21 以上 (`javac` と `java` が使えること。それ以外は不要)

## 🚀 クイックスタート

```bash
cd p1228-java
./build.sh          # フルビルド: クリーンコンパイル+単体テスト+p1228.jar生成 (Windows は build.bat)
./run.sh test       # 単体テストのみ (82件・ネットワーク不要。build.sh でも実行されます)
./run.sh commands   # BOTごとの Slash コマンド定義JSONを表示 (オフライン)
./run.sh demo       # フェイクDiscordで星燈機能を一通り動かす (オフライン)
```

実接続する場合:

1. `.env.example` を `.env` にコピーし、3 BOT分の Token、Application ID、相互に異なる Worker Secret (各32文字以上) を設定します。
2. 必要に応じて `config.json` (原本 `gateway.yaml` 相当) を編集します。`application_id` の `${ENV}` 展開、watching (128字まで)、intents は原本と同じ意味論です。
3. コマンドを同期してから起動します。

```bash
./run.sh sync-commands   # Discordへ Slash コマンドを登録/更新
./run.sh run             # 3体の Gateway 接続を開始
```

ビルド済み jar がある場合は `java -jar p1228.jar demo` でも起動できます。

## ⚙️ 設定

- `config.json`: Gateway 既定値 (内部概念として `127.0.0.1:8787`、リース30秒、試行8回、Interaction TTL 900秒、保持24時間)、3 BOT定義、Worker定義、保存先 (`data/store.json`)、VOICEVOX のURL (`voicevox_base_url`、既定 `http://127.0.0.1:50021`)
- `.env`: Token / Application ID / Worker Secret。`run` と `sync-commands` は厳格モードで読み込み、Token未設定・重複・プレースホルダ、Secret 32文字未満などをエラーにします。`commands` はオフライン用の緩和モードで読み込みます。
- プロキシ環境では環境変数 `HTTPS_PROXY` / `HTTP_PROXY` を自動で読みます (Discord REST / Gateway / VOICEVOX 共通)。

## 🧪 検証結果 (2026-11-17)

- ✅ コンパイル成功 (Temurin JDK 21.0.13)
- ✅ 単体テスト **82/82 パス** (JSON、Config検証、ポイント獲得/クールダウン/日次上限/台帳重複排除、抽選の勝敗・冪等リプレイ、おみくじの日次1回・冪等、ショップの検証/購入/残高不足/所有済み/pending再試行/付与完了の冪等、設定・辞書マージ、コマンド定義、Store再読み込み、Handlerの welcome/autorole/blacklist/sticky/VCログ/ロールパネル)
- ✅ `./run.sh commands` で3 BOT分の定義JSONが出力されることを確認
- ✅ `./run.sh demo` で GUILD_CREATE → 管理コマンド → 参加/メッセージ/おみくじ/ショップ表示/VCログ/読み上げ の一連の流れをフェイクDiscordで確認

## ⚠️ 正直な制約 (未実装・未検証)

- **Discord実接続は未検証です。** Gateway WebSocket クライアント (Hello/Heartbeat/Identify/Resume/再接続) と REST クライアントは実装済みですが、この開発VMでは Java プロセスからの直接TCPが権限設定で遮断されるため、実接続の確認はできていません。通常のPC/VPS環境では標準 `HttpClient` / `WebSocket` で動作する想定です。
- **Discord Voice の実転送は未実装です。** UDP/Opus/DAVE暗号化は Java 標準ライブラリだけでは実装できないため、`/join` `/leave` `/say` のロジック、設定・辞書適用、VOICEVOX合成、ギルド別キューは `VoiceTransport` インターフェースの手前まで実装し、既定実装はログ出力のみです。原本リポジトリでも Voice/DAVE E2E は未検証と明記されています。
- **永続化は SQLite ではなく単一JSONファイルです。** 全状態を `data/store.json` にアトミックに書き出します。原本のような複数プロセスからの同時アクセスは想定していません (単一JVM専用)。
- 原本の内部API (Worker認証、イベントリース/ACK、Inbox保持) は単一JVM化に伴い直接メソッド呼び出しに置換されています。Interaction 応答は `interaction_ref` 経由ではなく Discord の Interaction Callback / Webhook へ直接送ります。
- 読み上げテキスト整形 (URL・メンション等の簡略化) は原本より簡略化した実装です。音声キャッシュも実装していません。
- 既存BOTからの全機能移行、管理UI、辞書編集コマンドは原本同様に未実装です。

## 📚 ドキュメント

| ドキュメント                   | 内容                                         |
| ------------------------------ | -------------------------------------------- |
| [DESIGN.md](./DESIGN.md)       | 原本 Go/TS → Java の対応表、置換の判断と理由 |
| `config.json` / `.env.example` | 設定 (原本 `gateway.yaml` / `.env` 相当)     |

## 📂 構成

```
p1228-java/
  DESIGN.md / README.md
  config.json / .env.example
  build.sh / build.bat / run.sh
  src/main/java/p1228/
    Main.java          CLI (run / sync-commands / commands / demo)
    Json.java          軽量JSONパーサ/生成 (依存ゼロのため自作)
    Config.java        config.json 読み込みと検証 (Go config.go 移植)
    Store.java         ファイルベース永続化 (SQLite置換)
    StarFeatures.java  ポイント/抽選/おみくじ/ショップ (star_features.go 移植)
    Commands.java      Slashコマンド定義 (star-features.ts / sync-commands.ts 移植)
    StarHandler.java   星燈のイベント処理 (star-features.ts 移植、DiscordOps境界)
    ReadAloud.java     読み上げ設定/辞書/VOICEVOX/キュー (read-aloud 移植)
    DiscordRest.java   RESTクライアント (許可リスト6操作・operation_id冪等)
    GatewayClient.java Gateway WebSocketクライアント
    ProxySupport.java  環境変数プロキシ対応
  src/test/java/p1228/
    P1228Test.java     単体テスト 82件
  data/                store.json (自動生成・Git管理外)
```

---

<p align="center">
  <sub>p1228-java · Java 21 / 標準ライブラリのみ</sub>
</p>

## 👥 Contributors

[![All Contributors](https://img.shields.io/badge/all_contributors-1-orange.svg?style=flat-square)](#contributors)

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tbody>
    <tr>
      <td align="center" valign="top" width="14.28%"><a href="https://github.com/i-mabu"><img src="https://avatars.githubusercontent.com/u/356833989?v=4?s=100" width="100px;" alt="i-mabu"/><br /><sub><b>i-mabu</b></sub></a><br /><a href="https://github.com/i-mabu/p1228-java/commits?author=i-mabu" title="Code">💻</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->
