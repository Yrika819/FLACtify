#!/usr/bin/env python3
"""Create original FLACtify launcher icons with Pillow; no external artwork."""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SIZE = 512
TEAL = (43, 199, 199, 255)
NAVY = (35, 94, 151, 255)

image = Image.new("RGBA", (SIZE, SIZE), TEAL)
draw = ImageDraw.Draw(image)
# A hand-authored abstract waveform glyph inside a rounded frame.
frame = (112, 112, 400, 400)
draw.rounded_rectangle(frame, radius=68, outline=NAVY, width=28)
center_x = SIZE // 2
bars = [(190, 248), (246, 330), (302, 212), (358, 280)]
for x, height in bars:
    draw.rounded_rectangle(
        (x - 13, center_x - height // 2, x + 13, center_x + height // 2),
        radius=13,
        fill=NAVY,
    )

for density, pixels in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    resized = image.resize((pixels, pixels), Image.Resampling.LANCZOS)
    resized.save(folder / "ic_launcher.webp", "WEBP", lossless=True, method=6)
    circle = Image.new("L", (pixels, pixels), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, pixels - 1, pixels - 1), fill=255)
    round_icon = resized.copy()
    round_icon.putalpha(circle)
    round_icon.save(folder / "ic_launcher_round.webp", "WEBP", lossless=True, method=6)

image.convert("RGB").save(ROOT / "app/src/main/ic_launcher-playstore.png", "PNG", optimize=False)
