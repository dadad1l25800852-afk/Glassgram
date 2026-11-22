#!/bin/sh
set -eu
mkdir -p testmedia
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=640x360:rate=24:duration=5 \
  -f lavfi -i sine=frequency=440:sample_rate=44100:duration=5 \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 48 -sc_threshold 0 -c:a aac -b:a 96k \
  -f hls -hls_time 2 -hls_list_size 0 \
  -hls_segment_filename 'testmedia/segment%03d.ts' testmedia/playlist.m3u8
