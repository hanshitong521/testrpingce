#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/out"
rm -rf "$OUT"
mkdir -p "$OUT"
mapfile -t SOURCES < <(find "$ROOT/src" -name '*.java' -type f | sort)
if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo 'No Java source files found.' >&2
  exit 1
fi
javac --release 17 -Xlint:all -d "$OUT" "${SOURCES[@]}"
java -ea -cp "$OUT" bench.queue.TaskQueueTest
