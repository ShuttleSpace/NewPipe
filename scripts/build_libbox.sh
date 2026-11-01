#!/usr/bin/env bash
#
# Build the sing-box libbox Android AAR used by the built-in "Sing-box" proxy mode.
#
# The resulting app/libs/libbox.aar bundles the four Android ABIs and is ~270 MB
# uncompressed / ~92 MB zipped. It is intentionally NOT committed to git; it is
# published as a GitHub release asset (see doc/libbox.md) and downloaded on demand
# by the CI and by fresh checkouts.
#
# Requirements (see doc/libbox.md for details):
#   - Go (version matching sing-box go.mod)
#   - OpenJDK 17 (the official builder asserts "openjdk 17"; 21 will be rejected)
#   - Android SDK + NDK (ANDROID_HOME / ANDROID_NDK_HOME)
#   - git
#
# Usage:
#   SING_BOX_VERSION=v1.12.7 ./scripts/build_libbox.sh
#
set -euo pipefail

SING_BOX_VERSION="${SING_BOX_VERSION:-v1.12.7}"
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.13}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR_DIR="$ROOT/app/libs"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Cloning sing-box $SING_BOX_VERSION"
git clone --depth 1 --branch "$SING_BOX_VERSION" \
    https://github.com/SagerNet/sing-box.git "$WORKDIR/sing-box"

echo "==> Installing gomobile $GOMOBILE_VERSION"
go install "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VERSION"
export PATH="$PATH:$(go env GOPATH)/bin"

echo "==> gomobile init (requires the NDK)"
gomobile init

echo "==> Building libbox AAR for Android (all ABIs)"
cd "$WORKDIR/sing-box"
# The official builder (cmd/internal/build_libbox) sets the tags, the java package
# (io.nekohasekai) and the Android API levels. Do not add extra build tags.
go run ./cmd/internal/build_libbox -target android

echo "==> Copying libbox.aar -> $AAR_DIR"
mkdir -p "$AAR_DIR"
cp "$WORKDIR/sing-box/libbox.aar" "$AAR_DIR/libbox.aar"
ls -lh "$AAR_DIR/libbox.aar"
echo "Done. Publish app/libs/libbox.aar as a GitHub release asset named 'libbox'."
echo "The app's downloadLibbox Gradle task fetches it automatically when missing."
