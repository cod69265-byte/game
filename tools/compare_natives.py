"""Compare the JNI symbol sets of two libgdx.a builds.

The Java half of the port runs the game's own libGDX 1.1.0 bytecode. Whether it
can bind to a newer libgdx.a depends on one thing: does every native method the
1.1.0 Java code declares find its C symbol in the newer archive?

RoboVM derives JNI names as Java_com_badlogic_gdx_<class>_<method>, so we can
compare the exported symbol sets directly without an iOS toolchain.

Usage:
    python compare_natives.py <old.a> <new.a> [--verbose]
"""

import re
import struct
import sys


def slices(data):
    """Yield (cputype, cpusubtype, bytes) for each architecture in a fat file."""
    if len(data) < 8:
        return
    magic = struct.unpack(">I", data[:4])[0]
    if magic != 0xCAFEBABE:
        yield (0, 0, data)
        return
    count = struct.unpack(">I", data[4:8])[0]
    off = 8
    for _ in range(count):
        cputype, cpusub, offset, size, _align = struct.unpack(
            ">IIIII", data[off:off + 20])
        off += 20
        yield (cputype, cpusub, data[offset:offset + size])


def ar_members(blob):
    """Yield the raw bytes of every member of a Unix 'ar' archive."""
    if not blob.startswith(b"!<arch>\n"):
        return
    pos = 8
    longnames = b""
    while pos + 60 <= len(blob):
        header = blob[pos:pos + 60]
        name = header[0:16].decode("latin-1").strip()
        size_field = header[48:58].decode("latin-1").strip()
        try:
            size = int(size_field)
        except ValueError:
            return
        body = blob[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name == "//":
            longnames = body
            continue
        if name in ("/", "/SYM64/", "__.SYMDEF", "__.SYMDEF SORTED"):
            continue
        yield body


SYM = re.compile(rb"_(Java_com_badlogic_gdx_[A-Za-z0-9_]+)")


def jni_symbols(blob):
    """Collect every JNI entry-point name exported by an archive."""
    found = set()
    for member in ar_members(blob):
        for match in SYM.finditer(member):
            found.add(match.group(1).decode("ascii"))
    return found


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    verbose = "--verbose" in argv

    old = open(argv[1], "rb").read()
    new = open(argv[2], "rb").read()

    def arm_slice(blob, label):
        """Return the ARM slice, or the whole blob if it is already an archive."""
        if blob.startswith(b"!<arch>\n"):
            return blob  # already a thin ARM archive
        for cputype, _sub, part in slices(blob):
            if cputype == 12:
                return part
        print("no ARM slice in %s" % label)
        return None

    old_arm = arm_slice(old, argv[1])
    new_arm = arm_slice(new, argv[2])
    if old_arm is None or new_arm is None:
        return 1

    old_syms = jni_symbols(old_arm)
    new_syms = jni_symbols(new_arm)
    print("old JNI symbols: %d" % len(old_syms))
    print("new JNI symbols: %d" % len(new_syms))

    missing = sorted(old_syms - new_syms)
    added = sorted(new_syms - old_syms)
    print("missing in new: %d" % len(missing))
    print("added in new:   %d" % len(added))

    if verbose:
        for name in missing:
            print("  MISSING %s" % name)
        for name in added[:40]:
            print("  added   %s" % name)
    else:
        for name in missing[:40]:
            print("  MISSING %s" % name)
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
