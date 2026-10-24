#!/usr/bin/env python3
"""Fetch the transitive closure of the RoboVM Gradle plugin into offline-repo/.

The plugin's POM drags in a 2013-era dependency tree (aether, maven-wagon,
commons-compress). Gradle needs the *jars* of those, not just the POMs, when
running with --offline, so they have to be vendored too.

Usage:  python fetch_offline_repo.py [--root <ios-port dir>]
"""

import os
import sys
import urllib.request
import xml.etree.ElementTree as ET

MAVEN = "https://repo1.maven.org/maven2"
NS = "{http://maven.apache.org/POM/4.0.0}"

# Everything reachable from com.mobidevelop.robovm:robovm-gradle-plugin:2.3.19
# plus the libGDX artifacts the project itself declares. Kept explicit rather
# than crawled at build time so the result is reproducible and reviewable.
SEEDS = [
    ("com.mobidevelop.robovm", "robovm-gradle-plugin", "2.3.19"),
    ("com.mobidevelop.robovm", "robovm-compiler", "2.3.19"),
    ("com.mobidevelop.robovm", "robovm-rt", "2.3.19"),
    ("com.mobidevelop.robovm", "robovm-objc", "2.3.19"),
    ("com.mobidevelop.robovm", "robovm-cocoatouch", "2.3.19"),
    ("com.badlogicgames.gdx", "gdx", "1.14.2"),
    ("com.badlogicgames.gdx", "gdx-freetype", "1.14.2"),
    ("com.badlogicgames.gdx", "gdx-backend-robovm", "1.14.2"),
    ("com.badlogicgames.gdx", "gdx-backend-robovm-metalangle", "1.14.2"),
    ("com.badlogicgames.gdx", "gdx-platform", "1.14.2"),
    ("com.badlogicgames.gdx", "gdx-freetype-platform", "1.14.2"),
    ("com.badlogicgames.gdx", "gdx", "1.1.0"),
    ("com.badlogicgames.gdx", "gdx-freetype", "1.1.0"),
    ("com.badlogicgames.gdx", "gdx-backend-robovm", "1.1.0"),
    ("com.badlogicgames.gdx", "gdx-platform", "1.1.0"),
    ("com.badlogicgames.gdx", "gdx-freetype-platform", "1.1.0"),
]


def coords(group, artifact, version, packaging):
    ext = "jar" if packaging in ("jar", "bundle") else packaging
    path = "%s/%s/%s/%s-%s.%s" % (
        group.replace(".", "/"), artifact, version, artifact, version, ext)
    return path


def pom_path(group, artifact, version):
    return "%s/%s/%s/%s-%s.pom" % (
        group.replace(".", "/"), artifact, version, artifact, version)


def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True, "cached"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        with urllib.request.urlopen(url, timeout=180) as r:
            data = r.read()
    except Exception as exc:                                # noqa: BLE001
        return False, str(exc)
    with open(dest, "wb") as fh:
        fh.write(data)
    return True, "%d B" % len(data)


def parse_pom(data):
    """Return (packaging, [(group, artifact, version, scope, optional)]).

    Also follows <parent>, because Gradle refuses to resolve a POM whose parent
    is missing — the 2013-era tree is full of those (commons-parent:28,
    robovm-compiler-parent, forge-parent:10).
    """
    root = ET.fromstring(data)
    packaging = "jar"
    for child in root.findall(NS + "packaging"):
        packaging = (child.text or "jar").strip()
    parents = []
    for parent in root.findall(NS + "parent"):
        g = (parent.findtext(NS + "groupId", "") or "").strip()
        a = (parent.findtext(NS + "artifactId", "") or "").strip()
        v = (parent.findtext(NS + "version", "") or "").strip()
        if g and a and v and "${" not in v:
            parents.append((g, a, v))
    deps = []
    for dep in root.findall(".//" + NS + "dependency"):
        g = dep.findtext(NS + "groupId", "").strip()
        a = dep.findtext(NS + "artifactId", "").strip()
        v = dep.findtext(NS + "version", "").strip()
        scope = dep.findtext(NS + "scope", "compile").strip()
        opt = (dep.findtext(NS + "optional", "false") or "false").strip()
        if not g or not a or not v:
            continue
        if "${" in v or scope in ("test", "provided", "system"):
            continue
        deps.append((g, a, v, scope, opt == "true"))
    return packaging, deps, parents


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)))
    if "--root" in sys.argv:
        root = sys.argv[sys.argv.index("--root") + 1]
    repo = os.path.join(root, "offline-repo")

    seen = set()
    queue = list(SEEDS)
    failures = []
    fetched = 0

    while queue:
        group, artifact, version = queue.pop(0)
        key = (group, artifact, version)
        if key in seen:
            continue
        seen.add(key)

        # POM first: it tells us the packaging and the children.
        pom_rel = pom_path(group, artifact, version)
        pom_dest = os.path.join(repo, pom_rel.replace("/", os.sep))
        ok, info = fetch("%s/%s" % (MAVEN, pom_rel), pom_dest)
        if not ok:
            failures.append((pom_rel, info))
            continue
        fetched += 1

        try:
            with open(pom_dest, "rb") as fh:
                packaging, deps, parents = parse_pom(fh.read())
        except Exception as exc:                            # noqa: BLE001
            failures.append((pom_rel + " [parse]", str(exc)))
            continue

        jar_rel = coords(group, artifact, version, packaging)
        jar_dest = os.path.join(repo, jar_rel.replace("/", os.sep))
        ok, info = fetch("%s/%s" % (MAVEN, jar_rel), jar_dest)
        if ok:
            fetched += 1
        elif packaging != "pom":
            failures.append((jar_rel, info))

        for g, a, v, _scope, _opt in deps:
            if (g, a, v) not in seen:
                queue.append((g, a, v))
        # Parents are POM-only; the loop will fetch the pom and skip the jar
        # because packaging will resolve to "pom".
        for g, a, v in parents:
            if (g, a, v) not in seen:
                queue.append((g, a, v))

    print("artifacts touched: %d" % fetched)
    print("unique coordinates: %d" % len(seen))
    if failures:
        print("\nFAILURES (%d):" % len(failures))
        for rel, why in failures:
            print("  %-70s %s" % (rel, why))
        return 1
    print("\nOK — offline-repo is complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
