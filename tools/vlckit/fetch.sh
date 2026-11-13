#!/bin/sh
# Downloads the VLCKit frameworks the Apple app falls back to for streams AVPlayer can't
# decode (MP2 audio, MPEG-2 video, HEVC in MPEG-TS, MKV). They are several hundred MB,
# so they live in tvosApp/Vendor (git-ignored) instead of the repository.
# Official VideoLAN builds, pinned by version and checksum. Safe to run repeatedly.
set -eu

VERSION=3.7.3-319ed2c0-79128878
BASE=https://download.videolan.org/pub/cocoapods/prod
DEST="$(cd "$(dirname "$0")/../../tvosApp" && pwd)/Vendor"

fetch() { # name sha256
    [ -d "$DEST/$1.xcframework" ] && return
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    echo "Downloading $1 ${VERSION}…"
    curl -fL --progress-bar -o "$tmp/$1.tar.xz" "$BASE/$1-$VERSION.tar.xz"
    echo "$2  $tmp/$1.tar.xz" | shasum -a 256 -c - >/dev/null
    tar -xf "$tmp/$1.tar.xz" -C "$tmp"
    mkdir -p "$DEST"
    mv "$tmp/$1-binary/$1.xcframework" "$DEST/"
    cp "$tmp/$1-binary/COPYING.txt" "$DEST/$1-COPYING.txt"
    rm -rf "$tmp"
    trap - EXIT
}

fetch TVVLCKit b5f90c226ed54d9dc1c03901c60dc7749b74a53caace2c3047e4c0b7a063e46c
fetch MobileVLCKit 0d04059906962ddc9a7bd1ebaa12e1f9ae85eb2466116a97a2f46886dd27a0a9
echo "VLCKit is in $DEST"
