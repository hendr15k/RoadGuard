#!/bin/bash
set -e
# Resolve the repo root from this script's own location: the Gradle Exec task
# for :app runs with the module dir as cwd, not the root.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="$ROOT/src/main/assets/ufld_tusimple_float16.tflite"

MIN_BYTES=100000000  # real asset is 122,463,920 bytes

# Validate an already-present file too: a curl killed mid-transfer leaves a
# truncated file behind (its non-zero exit short-circuits any `&&` check), and
# an existence-only guard then accepted it forever — a green build shipping a
# broken model that TFLite rejects at runtime.
if [ -f "$MODEL" ]; then
  size=$(wc -c < "$MODEL")
  if [ "$size" -lt "$MIN_BYTES" ]; then
    echo "ufld model present but truncated ($size bytes) - removing" >&2
    rm -f "$MODEL"
  fi
fi

if [ ! -f "$MODEL" ]; then
  curl -fsSL -o "$MODEL" \
    https://github.com/hendr15k/RoadGuard/releases/download/v1.0.50-models/ufld_tusimple_float16.tflite
  size=$(wc -c < "$MODEL")
  if [ "$size" -lt "$MIN_BYTES" ]; then
    echo "UFLD model download incomplete ($size bytes)" >&2
    rm -f "$MODEL"
    exit 1
  fi
fi
