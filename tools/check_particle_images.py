"""Check that every image referenced by a .p particle file exists, case-sensitively.

Why: on the iPhone the game freezes right after tapping start. The device log stops
immediately after 153 `ResourceManager add: particle/*.p` lines, and
`ResourceManager.update()` swallows exceptions (`catch (Exception) { return false; }`),
so `waitLoadFinsh()`'s `while (!update())` spins forever. That turns any single asset
failure into a permanent freeze. Android and Windows filesystems are case-insensitive,
so a filename-case mismatch would load fine there and fail only on the iOS device's
case-sensitive bundle.
"""

import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "ios/resources"
PART = os.path.join(ROOT, "particle")

missing = []
checked = 0
for name in sorted(os.listdir(PART)):
    if not name.endswith(".p"):
        continue
    path = os.path.join(PART, name)
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    images = []
    for line in lines:
        s = line.strip()
        if s.startswith("image:") or s.startswith("image "):
            images.append(s.split(":", 1)[1].strip() if ":" in s else s.split(None, 1)[1].strip())
    for img in images:
        checked += 1
        base = img.replace("\\", "/").split("/")[-1]
        # The .p sits in particle/, images are resolved relative to that directory.
        cand = os.path.join(PART, base)
        if os.path.exists(cand):
            continue
        # Case-insensitive fallback: is there a file that differs only by case?
        alt = [e for e in os.listdir(PART) if e.lower() == base.lower()]
        missing.append((name, img, alt[0] if alt else None))

print("particle files checked, image refs =", checked)
print("missing =", len(missing))
for pf, img, alt in missing:
    if alt:
        print("  CASE  %-24s -> %-40s (on disk: %s)" % (pf, img, alt))
    else:
        print("  GONE  %-24s -> %s" % (pf, img))
