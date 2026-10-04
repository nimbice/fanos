"""python tools/download_button.py: docs/download-button.png, the README's download button, drawn at twice its shown
size. Copper, as the icon's lamp is, white Lexend (OFL, shipped with the app). It names no version, so it never needs
redrawing: the line under it in the README (release.sh keeps it current) says which build it fetches."""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "download-button.png")
FONT = os.path.join(ROOT, "feature", "reader", "src", "main", "assets", "reader", "fonts", "lexend.ttf")
W, H, R = 2000, 400, 200  # drawn at twice the file size, then scaled down for smooth edges
TOP, BOTTOM = (176, 92, 40), (122, 58, 26)


def lum(rgb):
    def ch(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (ch(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


for name, c in (("top", TOP), ("bottom", BOTTOM)):
    print(name, "white contrast %.2f" % (1.05 / (lum(c) + 0.05)))

# The copper body, top to bottom, inside a pill.
body = Image.new("RGB", (W, H))
d = ImageDraw.Draw(body)
for y in range(H):
    t = y / (H - 1)
    d.line([(0, y), (W, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
mask = Image.new("L", (W, H), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, H - 1], radius=R, fill=255)
out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
out.paste(body, (0, 0), mask)
d = ImageDraw.Draw(out)

# The download mark: a white disc with a copper arrow over a tray.
cx, cy, rad = 220, H // 2, 128
d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(255, 255, 255, 255))
ink = BOTTOM
d.rectangle([cx - 17, cy - 78, cx + 17, cy + 6], fill=ink)
d.polygon([(cx - 54, cy - 6), (cx + 54, cy - 6), (cx, cy + 52)], fill=ink)
d.rounded_rectangle([cx - 66, cy + 62, cx + 66, cy + 82], radius=10, fill=ink)

bold = ImageFont.truetype(FONT, 152)
bold.set_variation_by_name("Bold")
medium = ImageFont.truetype(FONT, 76)
medium.set_variation_by_name("Medium")
x = 428
d.text((x, 88), "Download Fanos", font=bold, fill=(255, 255, 255, 255))
d.text((x + 6, 264), "The app for Android · free", font=medium, fill=(255, 255, 255, 222))
right = x + max(d.textlength("Download Fanos", font=bold), d.textlength("The app for Android · free", font=medium))
assert right < W - 120, right
out = out.resize((W // 2, H // 2), Image.LANCZOS)
out.save(OUT, optimize=True)
print("wrote", OUT, out.size, "text ends at", int(right))
