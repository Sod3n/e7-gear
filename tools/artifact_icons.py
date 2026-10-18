#!/usr/bin/env python3
"""Cut the RTA helper's artifact icon sheet into <out>/<code>.webp (the content site's img/artifacts).

Usage: tools/artifact_icons.py <folder with item_manifest.json and items.webp> <out>
(the helper builds both from the game files: epic7/webapps/draft/static)
"""
import json, os, sys
from PIL import Image

src = sys.argv[1]
m = json.load(open(os.path.join(src, "item_manifest.json")))
sheet = Image.open(os.path.join(src, "items.webp")).convert("RGBA")
out = sys.argv[2]
os.makedirs(out, exist_ok=True)
w, h = m["tile"]
for code, (x, y) in m["artifacts"].items():
    sheet.crop((x, y, x + w, y + h)).save(os.path.join(out, f"{code}.webp"), "WEBP", quality=90, method=6)
print(len(m["artifacts"]), "icons")
