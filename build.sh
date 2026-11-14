#!/bin/sh
# Build the Rust player library and the release APK.
# Usage: ./build.sh [install]   (install = adb install to the connected phone)
set -e
cd "$(dirname "$0")"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${ANDROID_NDK_HOME:=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export ANDROID_HOME ANDROID_NDK_HOME

(cd player && cargo ndk -t arm64-v8a -P 29 -o ../app/src/main/jniLibs build --release)
gradle --no-daemon -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
echo "Built $APK"
if [ "$1" = install ]; then
    "$ANDROID_HOME/platform-tools/adb" install -r "$APK"
fi
