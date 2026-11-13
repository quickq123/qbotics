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
for f in libregexp.c libunicode.c cutils.c xsum.c; do
    [ -f "$f.o" ] || $CC $FLAGS -std=gnu11 -c "$SRC/$f" -I"$SRC" -o "$f.o" &
done
# The interpreter loop, split into one function per opcode so the JVM compiles it
# (split_interp.py; design doc §10.3). SPLIT=0 builds QuickJS as it comes, for comparison.
rm -f quickjs.c.o
if [ "${SPLIT:-1}" = "1" ]; then
    python3 /js/split_interp.py "$SRC/quickjs.c" /build/quickjs_split.c
    $CC $FLAGS -std=gnu11 -c /build/quickjs_split.c -I"$SRC" -o quickjs.c.o &
else
    $CC $FLAGS -std=gnu11 -c "$SRC/quickjs.c" -I"$SRC" -o quickjs.c.o &
fi
wait
$CC $FLAGS -std=gnu11 -c /js/bridge.c -I"$SRC" -o /build/bridge.o
# --no-wasm-opt: clang would run wasm-opt itself after linking, with its default inlining.
$CC --target=wasm32-wasip1 -O2 --no-wasm-opt -mexec-model=reactor -Wl,--gc-sections -Wl,-z,stack-size=524288 \
    -Wl,--allow-undefined -o /build/js.wasm /build/obj/*.o /build/bridge.o -lm
# -ocimfs: Binaryen would otherwise inline every function with one caller, putting the
# split interpreter (split_interp.py) back together into one function too big to compile.
wasm-opt -O2 -ocimfs=40 /build/js.wasm -o /js/out/js.wasm
wasm-objdump -x -j Import /js/out/js.wasm | tail -12
