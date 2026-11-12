#!/usr/bin/env bash
# Runs inside the toolchain container (see build.sh). The pinned litehtml is kept pristine in
# the qbot-web-build volume; each build copies it, applies patches.py and the extra sources,
# and recompiles only the files that changed since the last build.
set -euo pipefail

PRISTINE=/build/litehtml-pristine
if [ ! -d "$PRISTINE/.git" ] || [ "$(git -C "$PRISTINE" rev-parse HEAD)" != "$LITEHTML_COMMIT" ]; then
    rm -rf "$PRISTINE" /build/obj /build/compiled
    git clone -q https://github.com/litehtml/litehtml "$PRISTINE"
    git -C "$PRISTINE" checkout -q "$LITEHTML_COMMIT"
fi

SRC=/build/litehtml
rm -rf "$SRC"
cp -r "$PRISTINE" "$SRC"
python3 /web/patches.py "$SRC"
cp /web/extra/*.h "$SRC/include/litehtml/"
cp /web/extra/*.cpp "$SRC/src/"

CC=/opt/wasi-sdk/bin/clang
CXX=/opt/wasi-sdk/bin/clang++
FLAGS="--target=wasm32-wasip1 -Os -DNDEBUG -fdata-sections -ffunction-sections"
INC="-I$SRC/include -I$SRC/include/litehtml -I$SRC/src -I$SRC/src/gumbo/src"
mkdir -p /build/obj /build/compiled
# A header change recompiles everything; otherwise only the sources that changed.
HEADERS=$(cat "$SRC"/include/litehtml/*.h "$SRC"/src/*.h | sha1sum | cut -c1-40)
if [ "$(cat /build/compiled/headers 2>/dev/null)" != "$HEADERS" ]; then
    rm -f /build/obj/*.o /build/compiled/*.src
    echo "$HEADERS" > /build/compiled/headers
fi
compile() { # compiler, source, object, extra flags
    local kept="/build/compiled/$(basename "$3").src"
    if [ -f "$3" ] && cmp -s "$2" "$kept"; then return; fi
    $1 $FLAGS $4 -c "$2" -o "$3" && cp "$2" "$kept"
}
for f in "$SRC"/src/gumbo/src/*.c; do
    compile "$CC" "$f" "/build/obj/gumbo_$(basename "$f").o" "-std=c99 -I$SRC/src/gumbo/src" &
done
for f in "$SRC"/src/*.cpp; do
    compile "$CXX" "$f" "/build/obj/$(basename "$f").o" "-std=c++17 -fno-exceptions $INC" &
done
wait
$CXX $FLAGS -std=c++17 -fno-exceptions -c /web/bridge.cpp $INC -o /build/bridge.o
$CXX --target=wasm32-wasip1 -Os -mexec-model=reactor -fno-exceptions -Wl,--gc-sections -Wl,-z,stack-size=262144 \
    -Wl,--allow-undefined -o /build/web.wasm /build/obj/*.o /build/bridge.o
wasm-opt -Oz /build/web.wasm -o /web/out/web.wasm
