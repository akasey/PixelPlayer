#!/usr/bin/env bash
#
# Builds the userspace WireGuard engine into an Android AAR and installs it at
# app/libs/wireguard-netstack.aar so the app can link it with -Ppixelplay.enableWireguard=true.
#
# Requirements (NOT available in CI by default — run on a dev host):
#   - Go 1.22+            https://go.dev/dl/
#   - gomobile + gobind   go install golang.org/x/mobile/cmd/gomobile@latest
#                         go install golang.org/x/mobile/cmd/gobind@latest
#                         gomobile init
#   - Android SDK + NDK   (ANDROID_HOME / ANDROID_NDK_HOME set)
#
# The generated Java class is com.theveloper.pixelplay.wgnetstack.Wgnetstack, matching the
# reflective calls in NetstackWireGuardTunnel.kt.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
OUT_DIR="$REPO_ROOT/app/libs"
OUT_AAR="$OUT_DIR/wireguard-netstack.aar"

echo ">> Resolving Go modules"
cd "$HERE"
go mod tidy

echo ">> Building AAR with gomobile (arm64-v8a, armeabi-v7a)"
mkdir -p "$OUT_DIR"
# -extldflags max-page-size=16384 makes libgojni.so LOAD segments 16 KB-aligned, required for
# Android 15+ (16 KB page) devices. Without it the arm64 .so fails 16 KB compatibility checks.
gomobile bind \
  -target=android/arm64,android/arm \
  -androidapi 30 \
  -javapkg=com.theveloper.pixelplay \
  -ldflags "-extldflags=-Wl,-z,max-page-size=16384" \
  -o "$OUT_AAR" \
  .

echo ">> Done: $OUT_AAR"
echo "   Build the app with: ./gradlew :app:assembleDebug -Ppixelplay.enableWireguard=true"
