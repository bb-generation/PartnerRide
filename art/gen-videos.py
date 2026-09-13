#!/usr/bin/env python3
"""Generate art/videos/*.webp — the two screen recordings embedded in the README.

GitHub strips a `<video>` tag whose source is a file in the repository (only its own
drag-and-drop uploads get a player), so the clips are committed as animated WebP, which renders
inline like any other image. Both are cut from Karoo 3 screen recordings (ScreenCam, 1080x1800):

    python art/gen-videos.py <install-recording.mp4> <ride-recording.mp4>

- install.webp — installing and first-time setup. The two Android permission dialogs are only on
  screen for a second or two, so the button to tap is circled and the frame held briefly before
  each tap. Recording recording_20260913_200049.mp4.
- ride.webp — the field on a real ride with two riders. Recording recording_20260913_150605.mp4.

All times below are in the source recording's own timeline, in seconds. Needs ffmpeg built with
libwebp on PATH.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

OUT_DIR = Path(__file__).resolve().parent / "videos"

SRC_W, SRC_H = 1080, 1800   # recording resolution; ring coordinates below are in these pixels
OUT_W = 360                 # output width; height follows the aspect ratio
FPS = 15
RING_COLOR = (255, 45, 45, 255)
RING_WIDTH = 14
SUPERSAMPLE = 4             # rings are drawn large and downscaled, for a smooth edge

# Rings around the button to tap: (centre x, centre y, radius x, radius y), and the source time
# span they are shown for — from the dialog settling in place until it is tapped.
RING_WHILE_USING = ((540, 1372, 400, 95), 28.75, 29.6)   # location: "WHILE USING THE APP"
RING_ALLOW = ((540, 1377, 210, 90), 29.95, 31.7)         # nearby devices: "ALLOW"

HOLD = 1.5  # seconds each circled frame is held before the tap

# (start, end, seconds to hold the last frame) — played back to back. The first two segments end
# just before each tap, so their held last frame shows the circled button.
INSTALL_SEGMENTS = [
    (19.0, 29.55, HOLD),
    (29.55, 31.65, HOLD),
    (31.65, 90.0, 0),
    (120.0, 136.0, 0),   # 1:30-2:00 skipped
]
RIDE_SEGMENTS = [
    (289.0, 332.0, 0),   # 4:49-5:32
]


def draw_ring(path, cx, cy, rx, ry):
    k = SUPERSAMPLE
    img = Image.new("RGBA", (SRC_W * k, SRC_H * k), (0, 0, 0, 0))
    ImageDraw.Draw(img).ellipse(
        [(cx - rx) * k, (cy - ry) * k, (cx + rx) * k, (cy + ry) * k],
        outline=RING_COLOR, width=RING_WIDTH * k)
    img.resize((SRC_W, SRC_H), Image.LANCZOS).save(path)


def render(src, out, segments, rings=()):
    """Overlay the rings on the whole recording, then cut and join the segments.

    The overlay runs before trimming so ring times stay in the source timeline; fps is forced
    first because the recordings are variable frame rate, which trim and tpad handle poorly.
    """
    inputs = ["-i", str(src)]
    graph = [f"[0:v]fps={FPS}[v0]"]
    last = "v0"
    for i, (png, start, end) in enumerate(rings, 1):
        inputs += ["-i", str(png)]
        graph.append(f"[{last}][{i}:v]overlay=enable='between(t,{start},{end})'[v{i}]")
        last = f"v{i}"
    n = len(segments)
    graph.append(f"[{last}]scale={OUT_W}:-2:flags=lanczos,split={n}" + "".join(f"[s{i}]" for i in range(n)))
    for i, (start, end, hold) in enumerate(segments):
        pad = f",tpad=stop_mode=clone:stop_duration={hold}" if hold else ""
        graph.append(f"[s{i}]trim={start}:{end},setpts=PTS-STARTPTS{pad}[c{i}]")
    graph.append("".join(f"[c{i}]" for i in range(n)) + f"concat=n={n}:v=1:a=0[out]")

    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", *inputs,
         "-filter_complex", ";".join(graph), "-map", "[out]",
         "-c:v", "libwebp_anim", "-preset", "text", "-q:v", "70", "-compression_level", "6",
         "-loop", "0", str(out)],
        check=True)
    print(f"{out}  {out.stat().st_size / 1e6:.1f} MB")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    install_src, ride_src = map(Path, sys.argv[1:])
    OUT_DIR.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        rings = []
        for name, ((cx, cy, rx, ry), start, end) in [("while_using", RING_WHILE_USING),
                                                      ("allow", RING_ALLOW)]:
            png = Path(tmp) / f"ring_{name}.png"
            draw_ring(png, cx, cy, rx, ry)
            rings.append((png, start, end))
        render(install_src, OUT_DIR / "install.webp", INSTALL_SEGMENTS, rings)
    render(ride_src, OUT_DIR / "ride.webp", RIDE_SEGMENTS)


if __name__ == "__main__":
    main()
