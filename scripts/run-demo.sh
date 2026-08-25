#!/usr/bin/env bash
# Builds and runs the cpurest polyglot demo end to end:
#   1. tax-engine-rust   (Rust server on bus "/tax_engine")
#   2. java-backend-demo (Java client -> Rust, + Java server on bus "/java_backend")
#   3. validate_client   (Rust client -> Java, proving the other direction)
#
# library/ and examples/ are separate, independently buildable projects (not
# a shared reactor): the Java example resolves cpurest-java from the local
# Maven repository, so library/java is `mvn install`ed, not just packaged.
# cpurest-java's own only dependency, json-serializer, is a separate
# personal library (not cpurest-specific) that must be `mvn install`ed too
# — see docs/PRD.md's Known Limitations.
#
# Requires: cargo, mvn, and a `java` on PATH that's the JDK you want to run
# with (Java 22+; the Foreign Function & Memory API is stable there).
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

RUST_LOG="$(mktemp -t cpurest-tax-engine.XXXXXX)"
JAVA_LOG="$(mktemp -t cpurest-java-backend.XXXXXX)"
RUST_PID=""

cleanup() {
  if [[ -n "$RUST_PID" ]] && kill -0 "$RUST_PID" 2>/dev/null; then
    kill -TERM "$RUST_PID" 2>/dev/null || true
    wait "$RUST_PID" 2>/dev/null || true
  fi
  rm -f "$RUST_LOG" "$JAVA_LOG"
}
trap cleanup EXIT INT TERM

echo "==> Building cpurest Rust library..."
(cd "$LIB_RUST" && cargo build --release --workspace)

echo "==> Installing json-serializer into the local Maven repository..."
(cd "$JSON_SERIALIZER_DIR" && mvn -q -DskipTests clean install)

echo "==> Installing cpurest-java into the local Maven repository..."
(cd "$LIB_JAVA" && mvn -q -DskipTests clean install)

echo "==> Building Rust examples..."
(cd "$EX_RUST" && cargo build --release --workspace)

echo "==> Building java-backend-demo..."
(cd "$EX_JAVA_DEMO" && mvn -q -DskipTests clean package)

echo "==> Starting tax-engine-rust (bus \"/tax_engine\")..."
"$EX_RUST/target/release/tax-engine-rust" >"$RUST_LOG" 2>&1 &
RUST_PID=$!
sleep 0.5
if ! kill -0 "$RUST_PID" 2>/dev/null; then
  echo "tax-engine-rust failed to start:" >&2
  cat "$RUST_LOG" >&2
  exit 1
fi
cat "$RUST_LOG"

echo
echo "==> Starting java-backend-demo (client -> /tax_engine, server on /java_backend)..."
java --enable-native-access=ALL-UNNAMED \
  -jar "$EX_JAVA_DEMO/target/java-backend-demo.jar" >"$JAVA_LOG" 2>&1 &
JAVA_PID=$!

# java-backend-demo does its Rust round trip + latency benchmark immediately,
# then hosts /java_backend/validate for a 5s window — this is comfortably
# inside that window without needing to scrape the log for a "ready" line.
sleep 3

echo "==> Running validate_client (Rust -> Java bus \"/java_backend\")..."
(cd "$EX_RUST" && ./target/release/validate_client)

wait "$JAVA_PID"
echo
echo "==> java-backend-demo output:"
cat "$JAVA_LOG"

echo
echo "==> Demo complete."
