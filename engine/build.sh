#!/usr/bin/env bash
# Build engine/out/micropython.wasm inside the toolchain container.
# Usage: ./build.sh          (build)
#        ./build.sh clean    (remove intermediate files)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=qbot-engine-toolchain
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker build -t "$IMAGE" .
fi

# Intermediate objects live in a Docker volume: much faster than a Windows bind mount.
MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd -W 2>/dev/null || pwd)/port:/work/port:ro" \
    -v "$(pwd -W 2>/dev/null || pwd)/out:/work/out" \
    -v qbot-engine-build:/work/build \
    "$IMAGE" make -j"$(nproc)" "$@"

# The mod ships the split-interpreter build as a resource.
if [ -f out/micropython.wasm ]; then
    cp out/micropython.wasm ../common/src/main/resources/qbotics/engine/micropython.wasm
    # The editor reads the modules' source for its hover hints (qbotics.ide.Docs).
    mkdir -p ../common/src/main/resources/qbotics/rom
    cp port/rom/logistics.py port/rom/network.py port/rom/events.py port/rom/pip.py port/rom/display.py port/rom/qbot.py port/rom/requests.py ../common/src/main/resources/qbotics/rom/
fi
