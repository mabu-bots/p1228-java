#!/usr/bin/env bash
# p1228-java フルビルドスクリプト (JDK 21 が必要)
# 使い方: ./build.sh              -> クリーンビルド + 単体テスト + p1228.jar 生成
#         ./build.sh --skip-tests -> テストを省略 (notest でも可)
set -euo pipefail
cd "$(dirname "$0")"
# 日本語出力を文字化けさせない (POSIXロケール環境対策)
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"

# ===== 1. JDK の探索 (JAVA_HOME → PATH → 既知の配置場所) =====
find_jdk_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then echo "$JAVA_HOME"; return; fi
  if command -v javac >/dev/null 2>&1; then
    local p; p="$(command -v javac)"
    (cd "$(dirname "$p")/.." && pwd); return
  fi
  local c
  for c in "$HOME/workspace/tools/jdk-21" /usr/lib/jvm/java-21* /usr/lib/jvm/jdk-21* /usr/lib/jvm/*; do
    if [ -x "$c/bin/javac" ]; then echo "${c%/}"; return; fi
  done
  return 1
}
JDK_HOME="$(find_jdk_home)" || {
  echo "javac が見つかりません。JDK 21 をインストールするか JAVA_HOME を設定してください。" >&2; exit 1; }
JAVAC="$JDK_HOME/bin/javac"; JAR="$JDK_HOME/bin/jar"; JAVA="$JDK_HOME/bin/java"
echo "Using JDK: $JDK_HOME"
"$JAVAC" -version
VER="$("$JAVAC" -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
if [ "${VER:-0}" -lt 21 ] 2>/dev/null; then
  echo "JDK 21 以上が必要です (検出バージョン: $VER)。" >&2; exit 1
fi

SKIP_TESTS=0
case "${1:-}" in --skip-tests|notest) SKIP_TESTS=1 ;; esac

# ===== 2. クリーン =====
echo "== Clean =="
rm -rf out
rm -f p1228.jar
mkdir -p out

# ===== 3. コンパイル (main + test) =====
echo "== Compile =="
find src/main/java src/test/java -name '*.java' > out/sources.txt
"$JAVAC" -encoding UTF-8 -d out @out/sources.txt
rm -f out/sources.txt

# ===== 4. 単体テスト =====
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
if [ "$SKIP_TESTS" -eq 0 ]; then
  echo "== Test: p1228.P1228Test =="
  "$JAVA" $JAVA_OPTS -cp out p1228.P1228Test
else
  echo "== Test skipped =="
fi

# ===== 5. パッケージ =====
echo "== Package =="
"$JAR" cfe p1228.jar p1228.Main -C out .

SIZE="$(wc -c < p1228.jar | tr -d ' ')"
echo "Build OK"
echo "Artifact: $PWD/p1228.jar ($SIZE bytes)"
