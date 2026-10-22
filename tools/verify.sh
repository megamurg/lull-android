#!/usr/bin/env bash
# Compiles bedjetkit and the app's pure-Kotlin model layer with the standalone Kotlin compiler and runs their tests.
# This is the check that works on a machine without an Android SDK or Maven access; the real build is ./gradlew.
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC=${KOTLINC:-/opt/kotlinc-dist/kotlinc}
LIB=$KOTLINC/lib
OUT=build/verify
rm -rf "$OUT"; mkdir -p "$OUT/stub" "$OUT/kit" "$OUT/kit-test" "$OUT/model" "$OUT/model-test"
filter() { grep -v "Picked up JAVA_TOOL_OPTIONS" || true; }

echo "== stubs and runner"
javac -d "$OUT/stub" tools/stubs/org/junit/Test.java tools/LocalTestRunner.java 2>&1 | filter

COMPILE_CP="$OUT/stub:$LIB/kotlinx-coroutines-core-jvm.jar:$LIB/kotlin-test.jar:$LIB/kotlin-test-junit.jar"
RUN_CP="$OUT/stub:$LIB/kotlin-stdlib.jar:$LIB/kotlinx-coroutines-core-jvm.jar:$LIB/kotlin-test.jar"

echo "== bedjetkit: compile"
"$KOTLINC/bin/kotlinc" -Werror -cp "$COMPILE_CP" $(find bedjetkit/src/main/kotlin -name '*.kt') -d "$OUT/kit" 2>&1 | filter
echo "== bedjetkit: compile tests"
"$KOTLINC/bin/kotlinc" -cp "$COMPILE_CP:$OUT/kit" $(find bedjetkit/src/test/kotlin -name '*.kt') -d "$OUT/kit-test" 2>&1 | filter
KIT_TESTS=$(cd "$OUT/kit-test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')
echo "== bedjetkit: run tests: $KIT_TESTS"
java -cp "$RUN_CP:$OUT/kit:$OUT/kit-test" LocalTestRunner $KIT_TESTS 2>&1 | filter

if [ -d app/src/main/kotlin/com/lull/app/model ]; then
  echo "== app model: compile (serialization annotations stubbed)"
  "$KOTLINC/bin/kotlinc" -Werror -cp "$COMPILE_CP:$OUT/kit" tools/stubs/kotlinx/serialization/Stubs.kt \
      $(find app/src/main/kotlin/com/lull/app/model -name '*.kt') -d "$OUT/model" 2>&1 | filter
  if [ -d app/src/test/kotlin/com/lull/app/model ]; then
    echo "== app model: compile tests"
    "$KOTLINC/bin/kotlinc" -cp "$COMPILE_CP:$OUT/kit:$OUT/model" $(find app/src/test/kotlin/com/lull/app/model -name '*.kt') -d "$OUT/model-test" 2>&1 | filter
    MODEL_TESTS=$(cd "$OUT/model-test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')
    echo "== app model: run tests: $MODEL_TESTS"
    java -cp "$RUN_CP:$OUT/kit:$OUT/model:$OUT/model-test" LocalTestRunner $MODEL_TESTS 2>&1 | filter
  fi
  SER_RUNTIME=$(ls /opt/gradle-8.14.3/lib/kotlinx-serialization-core-jvm-*.jar 2>/dev/null | head -1)
  SER_JSON=$(ls /opt/gradle-8.14.3/lib/kotlinx-serialization-json-jvm-*.jar 2>/dev/null | head -1)
  SER_PLUGIN=$LIB/kotlinx-serialization-compiler-plugin.jar
  if [ -n "$SER_RUNTIME" ] && [ -n "$SER_JSON" ] && [ -f "$SER_PLUGIN" ]; then
    echo "== app model: real serialization plugin + JSON round-trip"
    mkdir -p "$OUT/model-ser"
    "$KOTLINC/bin/kotlinc" -Xplugin="$SER_PLUGIN" -cp "$COMPILE_CP:$OUT/kit:$SER_RUNTIME:$SER_JSON" \
        $(find app/src/main/kotlin/com/lull/app/model -name '*.kt') tools/roundtrip/JsonRoundTrip.kt -d "$OUT/model-ser" 2>&1 | filter
    java -cp "$RUN_CP:$OUT/kit:$OUT/model-ser:$SER_RUNTIME:$SER_JSON" com.lull.tools.JsonRoundTripKt 2>&1 | filter
  fi
fi
echo "== verify: all green"
