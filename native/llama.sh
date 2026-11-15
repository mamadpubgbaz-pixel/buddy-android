#!/usr/bin/env bash
# Adds llama.cpp's server to app/src/main/jniLibs/arm64-v8a, so Buddy can run a .gguf model chosen from the phone's
# storage (Settings → API keys → Model on this phone; see LocalModel.java). It is llama.cpp's own Android arm64 build,
# at a fixed release and checked against its SHA-256, not rebuilt here: the engine and its CPU variants (one per
# generation of Arm cores; the right one is picked when it starts). Only what llama-server needs is kept, stripped.
# Files must be called lib*.so to be unpacked next to the app's other native libraries; llama-server is a small
# launcher that finds its libraries through LD_LIBRARY_PATH (LocalModel sets it).
# With ANDROID_NDK_HOME set it uses that NDK's llvm-strip (CI); otherwise llvm-strip from the PATH.
set -euo pipefail
cd "$(dirname "$0")"
TAG=b12383
SHA256=f5d5cf77bebb468cb8041fd6dd2bfc3bbb7b3039f5c3e8e30a68761dd825c946
URL=https://github.com/ggml-org/llama.cpp/releases/download/$TAG/llama-$TAG-bin-android-arm64.tar.gz

OUT=$PWD/../app/src/main/jniLibs/arm64-v8a
WORK=$PWD/build/llama
STRIP=llvm-strip
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then STRIP=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip); fi
mkdir -p "$OUT" "$WORK"

curl -fsSL --retry 3 -o "$WORK/llama.tar.gz" "$URL"
echo "$SHA256  $WORK/llama.tar.gz" | sha256sum -c -
tar -xzf "$WORK/llama.tar.gz" -C "$WORK"
D=$WORK/llama-$TAG

cp "$D/llama-server" "$OUT/libllama-server.so"
for f in libllama-server-impl libllama-common libllama libggml libggml-base libmtmd; do cp "$D/$f.so" "$OUT/$f.so"; done
cp "$D"/libggml-cpu-android_*.so "$OUT/"
for f in "$OUT"/libllama*.so "$OUT"/libggml*.so "$OUT"/libmtmd.so; do "$STRIP" --strip-unneeded "$f"; done
echo "Added llama.cpp $TAG: $(du -sh "$OUT" | cut -f1) of native libraries in total"
