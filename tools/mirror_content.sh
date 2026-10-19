#!/bin/sh
# Copies the content site to the Google Cloud Storage mirror, which the app falls back to when *.pages.dev is
# unreachable (blocked by name in some countries). Images and content first, the manifest last, so a phone never
# reads a manifest that names a file the mirror doesn't have yet.
# Usage: tools/mirror_content.sh [site dir]   (default build/content-site, the last publish)
set -e
cd "$(dirname "$0")/.."
SITE=${1:-build/content-site}
MIRROR=gs://e7-gear-content
[ -f "$SITE/manifest.json" ] || { echo "no site in $SITE"; exit 1; }
gcloud storage rsync -r "$SITE/img" "$MIRROR/img" --cache-control="public, max-age=604800" --quiet
gcloud storage cp "$SITE"/content-*.json "$MIRROR/" --quiet
gcloud storage cp "$SITE/manifest.json" "$MIRROR/manifest.json" --cache-control="no-cache" --quiet
echo "mirrored $(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["file"])' "$SITE/manifest.json") to $MIRROR"
