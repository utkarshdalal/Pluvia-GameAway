#!/usr/bin/env bash
# Builds libgndownload.so (the Rust store-download engine behind GameDownloadService)
# for every Android ABI GameNative ships, and installs it into app/src/main/jniLibs.
#
# Prerequisites:
#   - Rust toolchain with the Android targets:
#       rustup target add aarch64-linux-android armv7-linux-androideabi
#   - Android NDK r27c (matches ndkVersion in app/build.gradle.kts); set
#     ANDROID_NDK_HOME or let the script probe $HOME/Android/Sdk/ndk/27.3.13750724.
set -euo pipefail

RUST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../app/src/main/cpp/gn-download/rust" && pwd)"
# AGP packages the default src/main/jniLibs for the modern flavor (only the Xr flavors
# override srcDirs) — installing anywhere else (e.g. src/jniLibs) silently ships a stale .so.
JNILIBS="$(cd "$RUST_DIR/../../.." && pwd)/jniLibs"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}"
HOST_TAG="linux-x86_64" # darwin-x86_64 on macOS

export PATH="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"
export CC_aarch64_linux_android=aarch64-linux-android26-clang
export AR_aarch64_linux_android=llvm-ar
export CC_armv7_linux_androideabi=armv7a-linux-androideabi26-clang
export AR_armv7_linux_androideabi=llvm-ar

cd "$RUST_DIR"

build_abi() {
    local target="$1" abi="$2"
    echo "==> cargo build --release --target $target"
    cargo build --release --target "$target"
    mkdir -p "$JNILIBS/$abi"
    cp "target/$target/release/libgndownload.so" "$JNILIBS/$abi/libgndownload.so"
    echo "    installed $JNILIBS/$abi/libgndownload.so"
}

build_abi aarch64-linux-android arm64-v8a
build_abi armv7-linux-androideabi armeabi-v7a

echo "Done. libgndownload.so is packaged from app/src/main/jniLibs (no Gradle native build)."
