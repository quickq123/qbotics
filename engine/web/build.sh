#!/usr/bin/env bash
# Build the web panel engine (litehtml + bridge.cpp) to engine/web/out/web.wasm, inside the
# toolchain container (../Dockerfile), and copy it into the mod's resources.
# Usage: engine/web/build.sh
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=qbot-engine-toolchain
# litehtml, pinned: bump deliberately, rebuild, and check the web_page game test's picture.
LITEHTML_COMMIT=a5ee957801d4aeb09ce7b365c7e2f9510e6272ff

mkdir -p out
MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd -W 2>/dev/null || pwd):/web" \
    -v qbot-web-build:/build \
    -e LITEHTML_COMMIT="$LITEHTML_COMMIT" \
    "$IMAGE" bash /web/build-inside.sh

cp out/web.wasm ../../common/src/main/resources/qbotics/web/web.wasm
ls -la out/web.wasm
