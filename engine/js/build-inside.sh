#!/usr/bin/env bash
# Runs inside the toolchain container (see build.sh). Sources and objects live in the
# qbot-js-build volume.
set -euo pipefail

SRC=/build/quickjs
if [ ! -d "$SRC/.git" ] || [ "$(git -C "$SRC" rev-parse HEAD)" != "$QUICKJS_COMMIT" ]; then
    rm -rf "$SRC" /build/obj
    git clone -q https://github.com/quickjs-ng/quickjs "$SRC"
    git -C "$SRC" checkout -q "$QUICKJS_COMMIT"
fi

CC=/opt/wasi-sdk/bin/clang
FLAGS="--target=wasm32-wasip1 -O2 -DNDEBUG -fdata-sections -ffunction-sections -D_WASI_EMULATED_SIGNAL -D_GNU_SOURCE"
mkdir -p /build/obj && cd /build/obj
for f in quickjs.c libregexp.c libunicode.c cutils.c xsum.c; do
    [ -f "$f.o" ] || $CC $FLAGS -std=gnu11 -c "$SRC/$f" -I"$SRC" -o "$f.o" &
done
wait
$CC $FLAGS -std=gnu11 -c /js/bridge.c -I"$SRC" -o /build/bridge.o
$CC --target=wasm32-wasip1 -O2 -mexec-model=reactor -Wl,--gc-sections -Wl,-z,stack-size=524288 \
    -Wl,--allow-undefined -o /build/js.wasm /build/obj/*.o /build/bridge.o -lm
wasm-opt -O2 /build/js.wasm -o /js/out/js.wasm
wasm-objdump -x -j Import /js/out/js.wasm | tail -12
