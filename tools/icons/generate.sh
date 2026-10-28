#!/usr/bin/env bash
# Regenerates the Android banner/launcher icons and the tvOS brand assets from render.swift.
set -euo pipefail
cd "$(dirname "$0")"
BIN=$(mktemp -d)/render-icon
swiftc -O render.swift -o "$BIN"
ROOT=../..

# Android TV: launcher banner (320x180 dp) and icon.
mkdir -p $ROOT/androidApp/src/main/res/drawable-xhdpi $ROOT/androidApp/src/main/res/mipmap-xxxhdpi
"$BIN" banner 640 360 $ROOT/androidApp/src/main/res/drawable-xhdpi/banner.png
"$BIN" square 192 192 $ROOT/androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png
# The side menu's logo (Compose can't draw the adaptive icon).
mkdir -p $ROOT/androidApp/src/main/res/drawable-xxxhdpi
"$BIN" square 192 192 $ROOT/androidApp/src/main/res/drawable-xxxhdpi/brand_mark.png
# Phones and tablets: adaptive icon layers (108 dp at xxxhdpi).
"$BIN" back 432 432 $ROOT/androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png
"$BIN" adaptive 432 432 $ROOT/androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png
rm -f $ROOT/androidApp/src/main/res/drawable/banner.xml

# tvOS: layered app icons + top shelf images.
A=$ROOT/tvosApp/Tuner/Assets.xcassets
B="$A/App Icon & Top Shelf Image.brandassets"
rm -rf "$A"; mkdir -p "$B"
info='"info":{"author":"xcode","version":1}'
echo "{$info}" > "$A/Contents.json"
cat > "$B/Contents.json" <<JSON
{"assets":[
 {"filename":"App Icon - App Store.imagestack","idiom":"tv","role":"primary-app-icon","size":"1280x768"},
 {"filename":"App Icon.imagestack","idiom":"tv","role":"primary-app-icon","size":"400x240"},
 {"filename":"Top Shelf Image Wide.imageset","idiom":"tv","role":"top-shelf-image-wide","size":"2320x720"},
 {"filename":"Top Shelf Image.imageset","idiom":"tv","role":"top-shelf-image","size":"1920x720"}],
 $info}
JSON
stack() { # name width height scales...
  local dir="$B/$1.imagestack" w=$2 h=$3; shift 3
  mkdir -p "$dir"
  echo "{\"layers\":[{\"filename\":\"Front.imagestacklayer\"},{\"filename\":\"Back.imagestacklayer\"}],$info}" > "$dir/Contents.json"
  for layer in Front Back; do
    local l="$dir/$layer.imagestacklayer/Content.imageset"; mkdir -p "$l"
    echo "{$info}" > "$dir/$layer.imagestacklayer/Contents.json"
    local kind; kind=$(echo $layer | tr 'A-Z' 'a-z')
    local images=""
    for scale in "$@"; do
      local f="$kind@${scale}x.png"
      "$BIN" "$kind" $((w * scale)) $((h * scale)) "$l/$f"
      images+="{\"filename\":\"$f\",\"idiom\":\"tv\",\"scale\":\"${scale}x\"},"
    done
    echo "{\"images\":[${images%,}],$info}" > "$l/Contents.json"
  done
}
stack "App Icon" 400 240 1 2
stack "App Icon - App Store" 1280 768 1
shelf() { # name width height
  local d="$B/$1.imageset"; mkdir -p "$d"
  "$BIN" banner $2 $3 "$d/shelf.png"
  "$BIN" banner $(($2 * 2)) $(($3 * 2)) "$d/shelf@2x.png"
  echo "{\"images\":[{\"filename\":\"shelf.png\",\"idiom\":\"tv\",\"scale\":\"1x\"},{\"filename\":\"shelf@2x.png\",\"idiom\":\"tv\",\"scale\":\"2x\"}],$info}" > "$d/Contents.json"
}
shelf "Top Shelf Image" 1920 720
shelf "Top Shelf Image Wide" 2320 720
# README / store artwork.
mkdir -p $ROOT/docs/brand
"$BIN" banner 1280 720 $ROOT/docs/brand/banner.png
"$BIN" square 512 512 $ROOT/docs/brand/icon.png
# Google Play feature graphic.
mkdir -p $ROOT/docs/store
"$BIN" banner 1024 500 $ROOT/docs/store/feature-graphic.png
echo "icons generated"
