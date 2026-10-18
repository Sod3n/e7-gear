"""Tile the screen audit's screenshots into one sheet per phone setup: app/build/audit/sheet_<setup>.png"""
import pathlib, sys
from PIL import Image, ImageDraw

d = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/audit")
shots = sorted(d.glob("*_*.png"))
for setup in sorted({p.name.split("_", 1)[0] for p in shots if not p.name.startswith("sheet")}):
    ims = [(p.stem.split("_", 1)[1], Image.open(p)) for p in shots if p.name.startswith(setup + "_")]
    w = max(i.width for _, i in ims) // 3
    h = max(i.height for _, i in ims) // 3
    cols = 8
    sheet = Image.new("RGB", (cols * (w + 8), ((len(ims) + cols - 1) // cols) * (h + 28)), "white")
    draw = ImageDraw.Draw(sheet)
    for n, (name, im) in enumerate(ims):
        x, y = (n % cols) * (w + 8), (n // cols) * (h + 28)
        sheet.paste(im.resize((im.width // 3, im.height // 3)), (x, y + 22))
        draw.text((x + 2, y + 4), name, fill="black")
    sheet.save(d / f"sheet_{setup}.png")
    print(d / f"sheet_{setup}.png", len(ims), "screens")
