#!/bin/sh
# Recreate the shared-directory links that this project relies on.
#
# Why this exists
# ---------------
# The three iOS flavours share ~64 MB of game resources and ~8 MB of native
# frameworks. On Windows those are NTFS junctions created with `mklink /J`.
# Junctions do not survive a copy to macOS (zip, rsync, scp, git checkout all
# turn them into empty directories or drop them), so the links have to be
# rebuilt on the machine that actually builds the app.
#
# What gets linked
# ----------------
#   ios26/resources      -> ios/resources       (1698 game assets + icons)
#   ios26/libs           -> ios/libs            (ios/ and ios26/ frameworks)
#   ios26scene/resources -> ios/resources
#   ios26scene/libs      -> ios/libs
#   ios26scene/src       -> ios26/src           (holds both launcher classes)
#
# Idempotent: existing links are left alone, empty directories are removed
# first, and a non-empty real directory is reported instead of being destroyed.
#
# Usage
# -----
#   cd ios-port && ./setupLinks.sh
#
# This script only creates symlinks. It does not touch build.gradle, the plists
# or any jar.

set -eu

cd "$(dirname "$0")"

link() {
    target=$1
    source=$2

    if [ -L "$target" ]; then
        existing=$(readlink "$target")
        if [ "$existing" = "$source" ]; then
            echo "ok    $target -> $source (already linked)"
            return 0
        fi
        echo "warn  $target -> $existing (wrong target, relinking)"
        rm -f "$target"
    elif [ -e "$target" ]; then
        if [ -d "$target" ] && [ -z "$(ls -A "$target" 2>/dev/null)" ]; then
            # A junction copied through zip/git arrives as an empty directory.
            rmdir "$target"
        else
            echo "FAIL  $target exists and is not empty; move it aside by hand"
            echo "      (a copied junction usually shows up as an empty dir,"
            echo "       so a non-empty one is probably real content)"
            return 1
        fi
    fi

    ln -s "$source" "$target"
    echo "link  $target -> $source"
}

fail=0

link "ios26/resources"      "../ios/resources"
link "ios26/libs"           "../ios/libs"
link "ios26scene/resources" "../ios/resources"
link "ios26scene/libs"      "../ios/libs"
link "ios26scene/src"       "../ios26/src"

# Sanity check: the two things the flavours cannot build without.
for probe in \
    "ios/resources/cocoUI" \
    "ios/libs/ios26/gdx.framework/gdx" \
    "ios26/src/main/java/com/soco/veggies3_4399/ios26/IOSLauncher26.java"
do
    if [ ! -e "$probe" ]; then
        echo "FAIL  missing after linking: $probe"
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    echo
    echo "One or more links could not be established. Check that the archive you"
    echo "unpacked contains ios/resources (1698 files) and ios/libs/ios26."
    exit 1
fi

echo
echo "All links are in place."
echo
echo "Next:"
echo "  ./gradlew :ios26:createIPA          # iOS 26.3 (legacy UIApplicationDelegate)"
echo "  ./gradlew :ios26scene:createIPA     # same, UIScene lifecycle (iOS 27 ready)"
