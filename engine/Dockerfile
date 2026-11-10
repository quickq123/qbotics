# Toolchain image for building the MicroPython engine to WebAssembly.
# Pinned versions: bump deliberately and re-run the benchmark harness afterwards.
FROM debian:bookworm-slim

ARG WASI_SDK_VERSION=34
ARG BINARYEN_VERSION=133
ARG MICROPYTHON_TAG=v1.29.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates curl git make python3 xz-utils wabt build-essential \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${WASI_SDK_VERSION}/wasi-sdk-${WASI_SDK_VERSION}.0-x86_64-linux.tar.gz" \
      | tar xz -C /opt \
    && mv "/opt/wasi-sdk-${WASI_SDK_VERSION}.0-x86_64-linux" /opt/wasi-sdk

RUN curl -fsSL "https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz" \
      | tar xz -C /opt \
    && mv "/opt/binaryen-version_${BINARYEN_VERSION}" /opt/binaryen

RUN git clone --depth 1 --branch "${MICROPYTHON_TAG}" \
      https://github.com/micropython/micropython /opt/micropython

ENV PATH="/opt/binaryen/bin:/opt/wasi-sdk/bin:${PATH}"
WORKDIR /work/port
