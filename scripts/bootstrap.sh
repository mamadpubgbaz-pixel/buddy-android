#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/libs"
if [[ ! -d "$ROOT/libs/llama.kt/.git" ]]; then
  rm -rf "$ROOT/libs/llama.kt"
  git clone --recurse-submodules https://github.com/tensai-app/llama.kt.git "$ROOT/libs/llama.kt"
fi
git -C "$ROOT/libs/llama.kt" submodule update --init --recursive
bash "$ROOT/libs/llama.kt/scripts/bootstrap.sh"
echo "llama.kt native sources prepared. Open Sepand AI in Android Studio or run ./gradlew :app:assembleDebug."
