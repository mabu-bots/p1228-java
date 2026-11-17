#!/usr/bin/env bash
# p1228-java ビルドスクリプト (JDK 21 が必要)
set -euo pipefail
cd "$(dirname "$0")"

find_javac() {
  if command -v javac >/dev/null 2>&1; then command -v javac; return; fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then echo "$JAVA_HOME/bin/javac"; return; fi
  for c in "$HOME/workspace/tools/jdk-21/bin/javac" /usr/lib/jvm/*/bin/javac; do
    if [ -x "$c" ]; then echo "$c"; return; fi
  done
  echo "javac が見つかりません。JDK 21 をインストールしてください。" >&2; exit 1
}
JAVAC="$(find_javac)"
JAR="$(dirname "$JAVAC")/jar"
echo "Using javac: $JAVAC"
rm -rf out
mkdir -p out
find src/main/java src/test/java -name '*.java' > sources.txt
"$JAVAC" -encoding UTF-8 -d out @sources.txt
rm -f sources.txt
if [ -x "$JAR" ]; then
  "$JAR" cfe p1228.jar p1228.Main -C out . >/dev/null 2>&1 || true
fi
echo "Build OK: out/ (and p1228.jar if jar tool available)"
