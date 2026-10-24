"""Generate the iOS icon / launch-image set for the Veggies3HD port.

App Store submission still needs a 1024x1024 marketing icon, but everything the
device itself looks at for iOS 8..12 is listed in ICONS / LAUNCH below.

Usage:
    python make_icons.py <icon-source.png> <launch-source.png> <out-dir>
"""

import os
import sys

from PIL import Image


# (filename, edge length) for iPhone + iPad app icons.
ICONS = [
    ("Icon.png", 57),
    ("Icon@2x.png", 114),
    ("Icon-72.png", 72),
    ("Icon-72@2x.png", 144),
    ("Icon-76.png", 76),
    ("Icon-76@2x.png", 152),
    ("Icon-60.png", 60),
    ("Icon-60@2x.png", 120),
    ("Icon-60@3x.png", 180),
    ("Icon-40.png", 40),
    ("Icon-40@2x.png", 80),
    ("Icon-40@3x.png", 120),
    ("Icon-29.png", 29),
    ("Icon-29@2x.png", 58),
    ("Icon-29@3x.png", 87),
    ("Icon-20.png", 20),
    ("Icon-20@2x.png", 40),
    ("Icon-20@3x.png", 60),
    ("Icon-83.5@2x.png", 167),
    ("Icon-50.png", 50),
    ("Icon-50@2x.png", 100),
    ("Icon-1024.png", 1024),
]

# Portrait launch images. The game is locked to portrait (533x854 logical), so
# only the portrait set is emitted; RoboVM pads the rest with the same art.
LAUNCH = [
    ("Default.png", (320, 480)),
    ("Default@2x.png", (640, 960)),
    ("Default@3x.png", (960, 1440)),
    ("Default-568h@2x.png", (640, 1136)),
    ("Default-667h@2x.png", (750, 1334)),
    ("Default-736h@3x.png", (1242, 2208)),
    ("Default-Portrait.png", (768, 1024)),
    ("Default-Portrait@2x.png", (1536, 2048)),
]


def contain(im, size, background):
    """Letterbox ``im`` into ``size`` without distorting it."""
    canvas = Image.new("RGB", size, background)
    scale = min(float(size[0]) / im.width, float(size[1]) / im.height)
    w = max(1, int(round(im.width * scale)))
    h = max(1, int(round(im.height * scale)))
    resized = im.resize((w, h), Image.LANCZOS)
    canvas.paste(resized, ((size[0] - w) // 2, (size[1] - h) // 2))
    return canvas


def cover(im, size, background):
    """Fill ``size`` with ``im``, cropping the overflow (no letterboxing)."""
    scale = max(float(size[0]) / im.width, float(size[1]) / im.height)
    w = max(1, int(round(im.width * scale)))
    h = max(1, int(round(im.height * scale)))
    resized = im.resize((w, h), Image.LANCZOS)
    left = (w - size[0]) // 2
    top = (h - size[1]) // 2
    return resized.crop((left, top, left + size[0], top + size[1])).convert("RGB")


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2

    icon_src, launch_src, out_dir = argv[1], argv[2], argv[3]
    os.makedirs(out_dir, exist_ok=True)

    icon = Image.open(icon_src).convert("RGBA")
    # iOS renders the icon with its own rounded mask and rejects transparency,
    # so flatten onto white first.
    flat = Image.new("RGB", icon.size, (255, 255, 255))
    flat.paste(icon, (0, 0), icon)
    icon = flat

    written = 0
    for name, edge in ICONS:
        out = os.path.join(out_dir, name)
        icon.resize((edge, edge), Image.LANCZOS).save(out, "PNG", optimize=True)
        written += 1

    launch = Image.open(launch_src).convert("RGB")
    for name, size in LAUNCH:
        out = os.path.join(out_dir, name)
        cover(launch, size, (0, 0, 0)).save(out, "PNG", optimize=True)
        written += 1

    print("icons=%d launch=%d total=%d dir=%s"
          % (len(ICONS), len(LAUNCH), written, out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
