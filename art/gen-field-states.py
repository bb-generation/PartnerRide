#!/usr/bin/env python3
"""Generate art/field-states/*.png — the data field images in the README's state table.

Unlike the other art/ scripts these are not drawn, they are cropped from photos of the real
thing: screenshots of the field in demo mode (TECHNICAL.md §7.3), which cycles every display
state on one device. A hand-drawn mock would drift from what the field actually renders; a crop
cannot.

Point it at a directory of demo-mode screenshots taken on the **map page**, numbered 1.png
upwards in cycle order — that page is the only one with a single Partner Gap field, so there is
no question which one got cropped:

    python art/gen-field-states.py <screenshot-dir>

The crop box is the field's view bounds, which demo mode's first frame reports (474x125 at
(3, 448) on a Karoo 3's map page — read it off frame 1 if a different page or device is used).
Corners are masked to the same radius the field's own background uses, so nothing of the map
behind it survives in the corners.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

DEFAULT_SRC = Path("debug4/2-rows_map-and-long")
OUT_DIR = Path(__file__).resolve().parent / "field-states"

BOX = (3, 448, 477, 573)    # x0, y0, x1, y1 — the field's view bounds within the screenshot
RADIUS = 19                 # px; field_corner_radius (10 dp) at the Karoo 3's 1.875 density
SUPERSAMPLE = 4             # mask is drawn large and downscaled, for a smooth corner edge

# Frame number in the demo cycle -> file name. The cycle is the config report, then the display
# states in FieldState order; frames the table does not use (10 m, 5 s / 60 s staleness) are
# simply not listed.
FRAMES = {
    2: "green-ahead",
    3: "green-behind",
    6: "yellow-ahead",
    7: "yellow-behind",
    8: "red-ahead",
    9: "red-behind",
    11: "last-known",
    13: "signal-lost",
    14: "no-signal",
    15: "no-gps",
    16: "no-bt",
    17: "no-code",
    18: "no-perm",
    19: "tap-to-start",
}


def rounded_mask(size):
    w, h = size
    mask = Image.new("L", (w * SUPERSAMPLE, h * SUPERSAMPLE), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, w * SUPERSAMPLE - 1, h * SUPERSAMPLE - 1), RADIUS * SUPERSAMPLE, fill=255,
    )
    return mask.resize((w, h), Image.LANCZOS)


def main():
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SRC
    if not src.is_dir():
        sys.exit(f"no such screenshot directory: {src}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    mask = rounded_mask((BOX[2] - BOX[0], BOX[3] - BOX[1]))
    for frame, name in FRAMES.items():
        image = Image.open(src / f"{frame}.png").convert("RGBA").crop(BOX)
        image.putalpha(mask)
        image.save(OUT_DIR / f"{name}.png", optimize=True)
        print(f"{name}.png  {image.width}x{image.height}")


if __name__ == "__main__":
    main()
