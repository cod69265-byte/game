"""Compare demanded JNI entry points against the symbols an archive exports.

NativeDecls renders each `native` method as Java_<class>_<method> (short form) or
with an overload suffix. libGDX's own native archives were not built by javah and
use a slightly different suffix spelling, so the comparison here is done on the
class+method prefix: a demanded entry point is satisfied when the archive exports
any symbol that starts with that prefix and then ends or continues with '__'.

That is exactly the condition the runtime cares about — the symbol either exists
or it does not.

Usage:
    python native_gap.py <decls.txt> <lib.a> [<lib.a> ...]
"""

import os
import re
import struct
import sys

SYMBOL = re.compile(rb"Java_com_badlogic_gdx_[A-Za-z0-9_]+")


def slices(data):
    if len(data) < 8 or struct.unpack(">I", data[:4])[0] != 0xCAFEBABE:
        yield data
        return
    count = struct.unpack(">I", data[4:8])[0]
    off = 8
    for _ in range(count):
        cputype, _sub, offset, size, _align = struct.unpack(
            ">IIIII", data[off:off + 20])
        off += 20
        yield data[offset:offset + size]


def ar_members(blob):
    if not blob.startswith(b"!<arch>\n"):
        return
    pos = 8
    while pos + 60 <= len(blob):
        header = blob[pos:pos + 60]
        name = header[0:16].decode("latin-1").strip()
        try:
            size = int(header[48:58].decode("latin-1").strip())
        except ValueError:
            return
        body = blob[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name in ("//", "/", "/SYM64/", "__.SYMDEF", "__.SYMDEF SORTED"):
            continue
        yield body


def arm_archive(path):
    data = open(path, "rb").read()
    if data.startswith(b"!<arch>\n"):
        return data
    for part in slices(data):
        if part.startswith(b"!<arch>\n"):
            return part
    # A thin slice may itself be a fat arm archive; take any slice that is one.
    for part in slices(data):
        if part[:8] == b"!<arch>\n":
            return part
    return None


def exported(path):
    blob = arm_archive(path)
    if blob is None:
        return None
    names = set()
    for member in ar_members(blob):
        for match in SYMBOL.finditer(member):
            names.add(match.group(0).decode("ascii"))
    return names


def base_name(symbol):
    """Strip the overload suffix: Java_a_b_Class_method__xyz -> Java_a_b_Class_method."""
    marker = symbol.find("__")
    return symbol if marker < 0 else symbol[:marker]


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2

    with open(argv[1], "r", encoding="utf-8") as handle:
        demanded = [line.strip() for line in handle if line.strip()]

    provided = set()
    for path in argv[2:]:
        names = exported(path)
        if names is None:
            print("%s: no arm archive found" % os.path.basename(path))
            continue
        print("%-28s exports %4d JNI symbols" % (os.path.basename(path), len(names)))
        provided |= names

    provided_bases = {base_name(s) for s in provided}

    missing = []
    for symbol in demanded:
        if base_name(symbol) not in provided_bases:
            missing.append(symbol)

    print("")
    print("demanded %d, satisfied %d, missing %d"
          % (len(demanded), len(demanded) - len(missing), len(missing)))
    for name in missing:
        print("   MISSING %s" % name)
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
