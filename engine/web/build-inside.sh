#!/usr/bin/env bash
# Runs inside the toolchain container (see build.sh). Sources and objects live in the
# qbot-web-build volume, so only what changed is fetched again.
set -euo pipefail

SRC=/build/litehtml
if [ ! -d "$SRC/.git" ] || [ "$(git -C "$SRC" rev-parse HEAD)" != "$LITEHTML_COMMIT" ]; then
    rm -rf "$SRC"
    git clone -q https://github.com/litehtml/litehtml "$SRC"
    git -C "$SRC" checkout -q "$LITEHTML_COMMIT"
    # Pages are always UTF-8, so litehtml's charset sniffing (the one place it throws a C++
    # exception) is cut out: the engine is built without exceptions.
    python3 - <<'EOF'
import io, re
p = "/build/litehtml/src/encodings.cpp"
t = io.open(p, encoding="utf-8").read()
t = t.replace("throw abort_prescan_exception(); // abort prescan", "__builtin_trap();")
t, n = re.subn(r"try\s*\{\s*return prescan_a_byte_stream_to_determine_its_encoding\(str\);\s*\}\s*catch\(abort_prescan_exception&\)\s*\{\s*return get_xml_encoding\(str\);\s*\}",
               "(void)str; return encoding::utf_8;", t)
assert n == 1 and "throw " not in t.replace("// abort prescan", ""), "the encodings patch no longer fits litehtml"
io.open(p, "w", encoding="utf-8").write(t)
EOF
    rm -rf /build/obj
fi

CC=/opt/wasi-sdk/bin/clang
CXX=/opt/wasi-sdk/bin/clang++
FLAGS="--target=wasm32-wasip1 -Os -DNDEBUG -fdata-sections -ffunction-sections"
INC="-I$SRC/include -I$SRC/include/litehtml -I$SRC/src -I$SRC/src/gumbo/src"
mkdir -p /build/obj && cd /build/obj
for f in "$SRC"/src/gumbo/src/*.c; do
    o="gumbo_$(basename "$f").o"
    [ -f "$o" ] || $CC $FLAGS -std=c99 -c "$f" -I"$SRC/src/gumbo/src" -o "$o" &
done
for f in "$SRC"/src/*.cpp; do
    o="$(basename "$f").o"
    [ -f "$o" ] || $CXX $FLAGS -std=c++17 -fno-exceptions -c "$f" $INC -o "$o" &
done
wait
$CXX $FLAGS -std=c++17 -fno-exceptions -c /web/bridge.cpp $INC -o /build/bridge.o
$CXX --target=wasm32-wasip1 -Os -mexec-model=reactor -fno-exceptions -Wl,--gc-sections -Wl,-z,stack-size=262144 \
    -Wl,--allow-undefined -o /build/web.wasm /build/obj/*.o /build/bridge.o
wasm-opt -Oz /build/web.wasm -o /web/out/web.wasm
