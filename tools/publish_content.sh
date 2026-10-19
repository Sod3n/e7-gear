#!/bin/sh
# Publishes game content so installed apps pick it up without an update: newest content, images and manifest to the
# e7-gear-content Cloudflare Pages project. Everything comes from our own decrypt of the game (E7_HANDOFF): after a game
# update, refresh the decrypt and game_base_stats.json first. The APK and the repo carry no game data;
# content/content.json (kept out of git) is the local copy the unit tests read.
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
E7_HANDOFF=${E7_HANDOFF:-$HOME/Downloads/HANDOFF-3}
[ -d "$E7_HANDOFF/images/face" ] || { echo "no decrypted images in $E7_HANDOFF (set E7_HANDOFF in tools/publish.local)"; exit 1; }
export E7_HANDOFF
export CLOUDFLARE_ACCOUNT_ID
# wrangler 3 needs Node 18+; this Mac's default nvm Node is 16
[ -d "$HOME/.nvm/versions/node/v18.20.8/bin" ] && PATH="$HOME/.nvm/versions/node/v18.20.8/bin:$PATH"

IMAGES=$E7_IMAGES
rm -rf "$SITE"; mkdir -p "$SITE"
# the fixed icons from the image store; portraits and artifact icons are added below from the decrypt
cp -R "$IMAGES" "$SITE/img"
python3 "$EPIC7/tools/phone_app_content/make_content.py" "$TMP/content.json"

# what players have now: the live site's content (or the local copy when the site can't be reached)
LIVE="$TMP/live.json"
if curl -sf -m 30 -o "$TMP/manifest.json" https://e7-gear-content.pages.dev/manifest.json; then
	curl -sf -m 60 -o "$LIVE" "https://e7-gear-content.pages.dev/$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["file"])' "$TMP/manifest.json")"
else
	cp content/content.json "$LIVE"
fi

python3 - "$TMP/content.json" "$LIVE" "$SITE" "$E7_HANDOFF/images" <<'EOF'
import hashlib, json, os, shutil, sys
from PIL import Image
src, shipped, site, game_img = sys.argv[1:]
c, old = json.load(open(src)), json.load(open(shipped))
# installed apps refuse content that loses an entry: keep what players already have and the decrypt lacks
for k in ("heroes", "artifacts"):
    have = {x["code"] for x in c[k]}
    kept = [x for x in old[k] if x["code"] not in have]
    c[k] += kept
    if kept: print(f"kept from the live content ({k}):", ", ".join(x["name"] or x["code"] for x in kept))
json.dump(c, open(src, "w"), separators=(",", ":"), ensure_ascii=False)
# a broken export must not reach players: it may only add heroes, artifacts and sets
for k in ("heroes", "artifacts", "sets"):
    assert len(c[k]) >= len(old[k]), f"new content has fewer {k} ({len(c[k])} < {len(old[k])}); not publishing"
assert c["version"] > old["version"], f"version {c['version']} isn't newer than {old['version']}"
# portraits of every named hero, artifact icons as the app asks for them (artifacts/<code>.webp)
os.makedirs(f"{site}/img/face"); os.makedirs(f"{site}/img/artifacts")
for h in c["heroes"]:
    if h["name"] and os.path.exists(f"{game_img}/face/{h['code']}_s.png"):
        shutil.copy(f"{game_img}/face/{h['code']}_s.png", f"{site}/img/face/")
no_icon = []
for a in c["artifacts"]:
    p = f"{game_img}/item_arti/{a.get('icon')}.png"
    if os.path.exists(p):
        Image.open(p).save(f"{site}/img/artifacts/{a['code']}.webp", "WEBP", lossless=True)
    else:
        no_icon.append(a["name"])
# the app falls back to the official site's icon for these
print("artifacts without a game icon:", ", ".join(no_icon) or "none")
name = "content-" + c["version"].replace(":", "") + ".json"
shutil.copy(src, f"{site}/{name}")
data = open(src, "rb").read()
json.dump({"version": c["version"], "schema": c["schema"], "file": name, "sha256": hashlib.sha256(data).hexdigest(),
           "heroes": len(c["heroes"]), "artifacts": len(c["artifacts"])}, open(f"{site}/manifest.json", "w"))
# every image the app can ask for must be on the site
img = f"{site}/img"
need = [f"icons/sets/{s['icon']}.png" for s in c["sets"]]
need += [f"icons/elements/element-{e}.png" for e in {h["element"] for h in c["heroes"] if h["element"]}]
need += [f"icons/classes/class-{r}.png" for r in {h["role"] for h in c["heroes"] if h["role"]}]
need += [f"icons/stats/stat-{s}.png" for s in ("att", "max_hp", "def", "speed", "cri", "cri_dmg", "acc", "res")]
need += [f"gear/gear_{g}.png" for g in ("weapon", "helm", "armor", "neck", "ring", "boot")]
need += [f"face/{h['code']}_s.png" for h in c["heroes"] if h["name"]]
missing = [p for p in need if not os.path.exists(f"{img}/{p}")]
assert not missing, "images the app needs aren't in the site: " + ", ".join(missing[:10])
print(f"images: {len(need)} checked, {sum(len(f) for _, _, f in os.walk(img))} on the site")
new = sorted({h["name"] or h["code"] for h in c["heroes"]} - {h["name"] or h["code"] for h in old["heroes"]})
print(f"{name}: {len(c['heroes'])} heroes, {len(c['artifacts'])} artifacts; new heroes: {', '.join(new) or 'none'}")
EOF

# Pages sends "max-age=0, must-revalidate" by default, and the app's image loader obeys it: every cached portrait cost a
# network round trip (5 at a time) before it showed. A redrawn image reaches phones within a week.
printf '/img/*\n  Cache-Control: public, max-age=604800\n' > "$SITE/_headers"

# the local copy the tests read; the app's own tests check it parses
mkdir -p content && cp "$TMP/content.json" content/content.json
./gradlew -q testDebugUnitTest --tests e7.gearscan.ContentUpdateTest --tests e7.gearscan.SourceRulesTest
if [ "$1" = "--dry-run" ]; then echo "dry run: site in $SITE, not deployed"; exit 0; fi
# the first publish creates the Pages project
npx --yes wrangler@3 pages project list | grep -q "e7-gear-content" ||
	npx --yes wrangler@3 pages project create e7-gear-content --production-branch main
npx --yes wrangler@3 pages deploy "$SITE" --project-name e7-gear-content --branch main --commit-dirty=true
