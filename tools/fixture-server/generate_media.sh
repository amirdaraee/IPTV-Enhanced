#!/usr/bin/env bash
# Generates small looping test streams (HLS segments + MP4) for the fixture server.
set -euo pipefail
cd "$(dirname "$0")"
OUT=media
mkdir -p "$OUT"
sources=(
  "testsrc2=size=1280x720:rate=25"
  "smptehdbars=size=1280x720:rate=25"
  "mandelbrot=size=1280x720:rate=25"
  "life=size=1280x720:rate=25:mold=10:ratio=0.1:death_color=#202060:life_color=#40c0ff"
)
freqs=(440 554 659 880)
for i in "${!sources[@]}"; do
  dir="$OUT/ch$i"
  [ -f "$dir/index.m3u8" ] && continue
  mkdir -p "$dir"
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "${sources[$i]}" -f lavfi -i "sine=frequency=${freqs[$i]}:sample_rate=48000" \
    -t 60 -c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 \
    -c:a aac -b:a 96k -ac 2 \
    -f hls -hls_time 2 -hls_list_size 0 -hls_segment_filename "$dir/seg%03d.ts" "$dir/index.m3u8"
done
if [ ! -f "$OUT/movie.mp4" ]; then
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=330:sample_rate=48000" \
    -t 300 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 96k -movflags +faststart "$OUT/movie.mp4"
fi
echo "media ready in $OUT"
