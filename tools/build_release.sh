#!/usr/bin/env bash
# Builds a signed release APK. The keystore lives outside the project and its password in the login keychain
# (service loopfollow-android-release, account keystore); neither is ever written into the project.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export LF_KEYSTORE_FILE="${LF_KEYSTORE_FILE:-$HOME/.android/loopfollow-android-release.jks}"
LF_KEYSTORE_PASSWORD="$(security find-generic-password -s loopfollow-android-release -a keystore -w)"
export LF_KEYSTORE_PASSWORD
export LF_KEY_ALIAS=loopfollow
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
./gradlew --quiet assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$APK" | grep -E "Signer #1 certificate (DN|SHA-256)"
echo "$APK"
