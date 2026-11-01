#!/bin/bash
# Renders review screens on the running emulator from a fixture box and writes an HTML gallery.
# Usage: E7_FIXTURES=<dir with backup.json + content.json> tools/gallery.sh [test method, default sell] [out dir]
set -euo pipefail
cd "$(dirname "$0")/.."
METHOD="${1:-sell}"
OUT="${2:-app/build/gallery}"
: "${E7_FIXTURES:?set E7_FIXTURES to a folder with backup.json and content.json}"
adb get-state >/dev/null
./gradlew -q :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="e7.gearscan.GalleryScreens#$METHOD" \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
rm -rf "$OUT" && mkdir -p "$OUT"
adb pull /sdcard/Android/data/e7.gearscan/files/gallery/. "$OUT/" >/dev/null
ls "$OUT"/*.png >/dev/null
{
  echo '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>E7 Gear screens</title>'
  echo '<style>body{margin:0;padding:16px;font:14px system-ui;background:#eee}figure{display:inline-block;margin:0 12px 18px 0;vertical-align:top}img{width:320px;max-width:100%;border:1px solid #999;border-radius:8px}figcaption{font-size:12px;color:#444}</style>'
  for f in $(ls "$OUT" | grep '\.png$' | sort); do echo "<figure><img src=\"$f\" loading=\"lazy\"><figcaption>${f%.png}</figcaption></figure>"; done
} > "$OUT/index.html"
echo "$OUT/index.html ($(ls "$OUT"/*.png | wc -l | tr -d ' ') screens)"
