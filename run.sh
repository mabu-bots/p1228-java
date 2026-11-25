#!/usr/bin/env bash
# p1228-java 起動スクリプト
# 使い方: ./run.sh [test|commands|demo|sync-commands|run]
set -euo pipefail
cd "$(dirname "$0")"
# 日本語引数・出力を文字化けさせない (POSIXロケール環境対策)
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

find_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return; fi
  if command -v java >/dev/null 2>&1; then command -v java; return; fi
  for c in "$HOME/workspace/tools/jdk-25/bin/java" /usr/lib/jvm/*/bin/java; do
    if [ -x "$c" ]; then echo "$c"; return; fi
  done
  echo "java が見つかりません。JDK/JRE 25 をインストールしてください。" >&2; exit 1
}
JAVA="$(find_java)"

if [ ! -d out ]; then
  ./build.sh
fi

CMD="${1:-demo}"
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dnative.encoding=UTF-8 -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes= --enable-native-access=ALL-UNNAMED"
if [ "$CMD" = "test" ]; then
  exec "$JAVA" $JAVA_OPTS -cp out p1228.P1228Test
fi
exec "$JAVA" $JAVA_OPTS -cp out p1228.Main "$@"
