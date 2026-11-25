#!/usr/bin/env bash
# p1228-java フルビルドスクリプト (JDK 25 が必要: FFM の正式版を使うため)
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
  for c in "$HOME/workspace/tools/jdk-25" /usr/lib/jvm/java-25* /usr/lib/jvm/jdk-25* /usr/lib/jvm/*; do
    if [ -x "$c/bin/javac" ]; then echo "${c%/}"; return; fi
  done
  return 1
}
JDK_HOME="$(find_jdk_home)" || {
  echo "javac が見つかりません。JDK 25 をインストールするか JAVA_HOME を設定してください。" >&2; exit 1; }
JAVAC="$JDK_HOME/bin/javac"; JAR="$JDK_HOME/bin/jar"; JAVA="$JDK_HOME/bin/java"
echo "Using JDK: $JDK_HOME"
"$JAVAC" -version
VER="$("$JAVAC" -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
if [ "${VER:-0}" -lt 25 ] 2>/dev/null; then
  echo "JDK 25 以上が必要です (検出バージョン: $VER)。FFM (java.lang.foreign) の正式版を使うためです。" >&2; exit 1
fi

SKIP_TESTS=0
case "${1:-}" in --skip-tests|notest) SKIP_TESTS=1 ;; esac

# ===== 2. クリーン =====
echo "== Clean =="
rm -rf out
rm -f p1228.jar
mkdir -p out

# ===== 3. コンパイル (main + test) =====
# Concentus (純Java Opus) は vendored jar をクラスパスに載せる。
CONCENTUS_JAR="lib/concentus-1.0.2.jar"
echo "== Compile =="
find src/main/java src/test/java -name '*.java' > out/sources.txt
"$JAVAC" -encoding UTF-8 -cp "$CONCENTUS_JAR" -d out @out/sources.txt
rm -f out/sources.txt
# Concentus のクラスと libdave を out へ展開し、単一 jar に同梱する。
(cd out && "$JAR" xf "../$CONCENTUS_JAR")
mkdir -p out/native/linux-x86-64
cp lib/native/linux-x86-64/libdave.so out/native/linux-x86-64/libdave.so

# ===== 4. 単体テスト =====
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED"
if [ "$SKIP_TESTS" -eq 0 ]; then
  echo "== Test: p1228.P1228Test =="
  "$JAVA" $JAVA_OPTS -cp out p1228.P1228Test
else
  echo "== Test skipped =="
fi

# ===== 5. パッケージ =====
# Enable-Native-Access: libdave (FFM) のネイティブアクセス許可。
echo "== Package =="
printf 'Manifest-Version: 1.0\nMain-Class: p1228.Main\nEnable-Native-Access: ALL-UNNAMED\n' > out/manifest.mf
"$JAR" cfm p1228.jar out/manifest.mf -C out .
rm -f out/manifest.mf

SIZE="$(wc -c < p1228.jar | tr -d ' ')"
echo "Build OK"
echo "Artifact: $PWD/p1228.jar ($SIZE bytes)"
