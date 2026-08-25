#!/usr/bin/env bash
# Starts the interactive demo: tax-engine-rust and java-backend-demo (in
# --serve mode, staying up indefinitely) as the two servers, plus the
# demo-ui HTTP bridge, then leaves all three running in the foreground.
# Open http://127.0.0.1:8089 in a browser once it prints "listening".
# Ctrl+C stops all three and cleans up their shared-memory segments.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_RUST="$ROOT_DIR/library/rust"
LIB_JAVA="$ROOT_DIR/library/java"
EX_RUST="$ROOT_DIR/examples/rust"
EX_JAVA_DEMO="$ROOT_DIR/examples/java/java-backend-demo"
JSON_SERIALIZER_DIR="$HOME/darshan/json-serializer"

if [[ ! -d "$JSON_SERIALIZER_DIR" ]]; then
  echo "error: json-serializer not found at $JSON_SERIALIZER_DIR (cpurest-java depends on it — see docs/PRD.md)" >&2
  exit 1
fi

PIDS=()
cleanup() {
  for pid in "${PIDS[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> Building cpurest Rust library and examples..."
(cd "$LIB_RUST" && cargo build --release --workspace)
(cd "$EX_RUST" && cargo build --release --workspace)

echo "==> Installing json-serializer and cpurest-java, and building java-backend-demo..."
(cd "$JSON_SERIALIZER_DIR" && mvn -q -DskipTests clean install)
(cd "$LIB_JAVA" && mvn -q -DskipTests clean install)
(cd "$EX_JAVA_DEMO" && mvn -q -DskipTests clean package)

echo "==> Starting tax-engine-rust..."
"$EX_RUST/target/release/tax-engine-rust" &
PIDS+=($!)
sleep 0.5

echo "==> Starting java-backend-demo --serve..."
java --enable-native-access=ALL-UNNAMED -jar "$EX_JAVA_DEMO/target/java-backend-demo.jar" --serve &
PIDS+=($!)
sleep 0.5

echo "==> Starting demo-ui bridge..."
"$EX_RUST/target/release/demo-ui" &
PIDS+=($!)

echo
echo "==> Open http://127.0.0.1:8089 — Ctrl+C here to stop everything."
wait
