#!/bin/sh
# Publishes game content so installed apps pick it up without an update: newest content, images and manifest to the
# e7-gear-content Cloudflare Pages project. The APK and the repo carry no game data; content/content.json (kept out of
# git) is the local copy the unit tests read.
# Usage: tools/publish_content.sh [--dry-run]
# Needs a Cloudflare token with Pages:Edit (CLOUDFLARE_API_TOKEN or E7_PAGES_TOKEN); the first run creates the project.
set -e
cd "$(dirname "$0")/.."
SITE=build/content-site
TMP=$(mktemp -d)
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
# CLOUDFLARE_API_TOKEN (from the Keychain via ~/.zshenv) needs Account > Cloudflare Pages > Edit; E7_PAGES_TOKEN overrides it
[ -n "$E7_PAGES_TOKEN" ] && export CLOUDFLARE_API_TOKEN="$E7_PAGES_TOKEN"
# tell wrangler the account, so it never has to list them
# machine-specific settings (CLOUDFLARE_ACCOUNT_ID, EPIC7) stay out of the repo, in tools/publish.local
[ -f tools/publish.local ] && . tools/publish.local
: "${CLOUDFLARE_ACCOUNT_ID:?set CLOUDFLARE_ACCOUNT_ID in tools/publish.local}"
: "${EPIC7:?set EPIC7 (the checkout with make_content.py and the game tables) in tools/publish.local}"
: "${E7_IMAGES:?set E7_IMAGES (the game image store) in tools/publish.local}"
export CLOUDFLARE_ACCOUNT_ID
# wrangler 3 needs Node 18+; this Mac's default nvm Node is 16
[ -d "$HOME/.nvm/versions/node/v18.20.8/bin" ] && PATH="$HOME/.nvm/versions/node/v18.20.8/bin:$PATH"

IMAGES=$E7_IMAGES
rm -rf "$SITE"; mkdir -p "$SITE"
# game-derived images: the fixed ones from the image store, artifact icons re-cut from the helper's current sheet
cp -R "$IMAGES" "$SITE/img"
python3 tools/artifact_icons.py "$EPIC7/webapps/draft/static" "$SITE/img/artifacts" >/dev/null

# Fribbels' latest hero and artifact data: names, elements and EE for heroes newer than the local copy
for f in herodata artifactdata; do
	curl -sf -m 60 -o "$TMP/$f.json" "https://raw.githubusercontent.com/fribbels/Fribbels-Epic-7-Optimizer/main/data/cache/$f.json"
done
E7_HERODATA="$TMP/herodata.json" E7_ARTIFACTDATA="$TMP/artifactdata.json" \
	python3 "$EPIC7/tools/phone_app_content/make_content.py" "$TMP/content.json"

# what players have now: the live site's content (or the local copy when the site can't be reached)
LIVE="$TMP/live.json"
if curl -sf -m 30 -o "$TMP/manifest.json" https://e7-gear-content.pages.dev/manifest.json; then
	curl -sf -m 60 -o "$LIVE" "https://e7-gear-content.pages.dev/$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["file"])' "$TMP/manifest.json")"
else
	cp content/content.json "$LIVE"
fi

python3 - "$TMP/content.json" "$LIVE" "$SITE" <<'EOF'
import hashlib, json, shutil, sys
src, shipped, site = sys.argv[1:]
c, old = json.load(open(src)), json.load(open(shipped))
# a broken export must not reach players: it may only add heroes, artifacts and sets
for k in ("heroes", "artifacts", "sets"):
    assert len(c[k]) >= len(old[k]), f"new content has fewer {k} ({len(c[k])} < {len(old[k])}); not publishing"
assert c["version"] > old["version"], f"version {c['version']} isn't newer than {old['version']}"
name = "content-" + c["version"].replace(":", "") + ".json"
shutil.copy(src, f"{site}/{name}")
data = open(src, "rb").read()
json.dump({"version": c["version"], "schema": c["schema"], "file": name, "sha256": hashlib.sha256(data).hexdigest(),
           "heroes": len(c["heroes"]), "artifacts": len(c["artifacts"])}, open(f"{site}/manifest.json", "w"))
# every image the app can ask for must be on the site
import os
img = f"{site}/img"
need = [f"icons/sets/{s['icon']}.png" for s in c["sets"]]
need += [f"icons/elements/element-{e}.png" for e in {h["element"] for h in c["heroes"] if h["element"]}]
need += [f"icons/classes/class-{r}.png" for r in {h["role"] for h in c["heroes"] if h["role"]}]
need += [f"icons/stats/stat-{s}.png" for s in ("att", "max_hp", "def", "speed", "cri", "cri_dmg", "acc", "res")]
need += [f"gear/gear_{g}.png" for g in ("weapon", "helm", "armor", "neck", "ring", "boot")]
# neither the helper's sheet nor the official site has these two
need += [f"artifacts/{a['code']}.webp" for a in c["artifacts"] if a["code"] not in ("ef437", "ef416")]
missing = [p for p in need if not os.path.exists(f"{img}/{p}")]
assert not missing, "images the app needs aren't in the site: " + ", ".join(missing[:10])
print(f"images: {len(need)} checked, {sum(len(f) for _, _, f in os.walk(img))} on the site")
new = sorted({h["name"] or h["code"] for h in c["heroes"]} - {h["name"] or h["code"] for h in old["heroes"]})
print(f"{name}: {len(c['heroes'])} heroes, {len(c['artifacts'])} artifacts; new heroes: {', '.join(new) or 'none'}")
EOF

# the local copy the tests read; the app's own tests check it parses
mkdir -p content && cp "$TMP/content.json" content/content.json
./gradlew -q testDebugUnitTest --tests e7.gearscan.ContentUpdateTest --tests e7.gearscan.SourceRulesTest
if [ "$1" = "--dry-run" ]; then echo "dry run: site in $SITE, not deployed"; exit 0; fi
# the first publish creates the Pages project
npx --yes wrangler@3 pages project list | grep -q "e7-gear-content" ||
	npx --yes wrangler@3 pages project create e7-gear-content --production-branch main
npx --yes wrangler@3 pages deploy "$SITE" --project-name e7-gear-content --branch main --commit-dirty=true
