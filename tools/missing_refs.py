import zipfile, sys, re, struct

jar = sys.argv[1]
z = zipfile.ZipFile(jar)

# 目标：找出引用这些名字的 (owner, name, desc)
WANT = {
    'getTextureRegions', 'getColorBufferTexture', 'getRegions', 'getScale',
    'getSprite', 'obtain', 'getTextInput', 'setCatchBackKey', 'setCatchMenuKey',
    'setScale', 'getBounds', 'draw',
}

def parse_pool(data):
    """返回常量池，index -> ('class', name) / ('nat', name, desc) / ('mref', owner, name, desc)"""
    cnt = struct.unpack('>H', data[8:10])[0]
    pool = {}
    i = 1
    off = 10
    raw = {}
    while i < cnt:
        tag = data[off]; off += 1
        if tag == 1:  # Utf8
            ln = struct.unpack('>H', data[off:off+2])[0]; off += 2
            raw[i] = data[off:off+ln].decode('utf-8', 'replace'); off += ln
        elif tag in (7, 8, 16, 19, 20):  # Class/String/MethodType/Module/Package
            raw[i] = ('ref1', struct.unpack('>H', data[off:off+2])[0]); off += 2
        elif tag in (15,):  # MethodHandle
            off += 3
        elif tag in (3, 4):  # Integer/Float
            off += 4
        elif tag in (5, 6):  # Long/Double
            off += 8; i += 1
        elif tag in (9, 10, 11, 12, 17, 18):  # Field/Method/IMethod/NameAndType/Dynamic/InvokeDynamic
            raw[i] = (struct.unpack('>H', data[off:off+2])[0], struct.unpack('>H', data[off+2:off+4])[0])
            off += 4
        else:
            raise ValueError('bad tag %d at %d' % (tag, off))
        i += 1
    # resolve
    for k, v in raw.items():
        if isinstance(v, tuple) and v and v[0] == 'ref1':
            t = raw.get(v[1])
            pool[k] = t if isinstance(t, str) else None
        elif isinstance(v, str):
            pool[k] = v
        elif isinstance(v, tuple) and len(v) == 2:
            a, b = raw.get(v[0]), raw.get(v[1])
            pool[k] = (a, b)
        else:
            pool[k] = None
    return pool

found = {}
for n in z.namelist():
    if not n.endswith('.class'):
        continue
    if n.startswith('com/badlogic/gdx/') or n.startswith('com/esotericsoftware/'):
        continue
    data = z.read(n)
    try:
        pool = parse_pool(data)
    except Exception as e:
        continue
    for k, v in pool.items():
        if isinstance(v, tuple) and len(v) == 2 and isinstance(v[1], tuple):
            owner, nat = v
            if isinstance(nat, tuple) and len(nat) == 2:
                nm, desc = nat
                if isinstance(nm, str) and nm in WANT and isinstance(desc, str):
                    key = (owner, nm, desc)
                    found.setdefault(key, set()).add(n)

print('=== 精确缺失引用 (owner, name, desc) ===')
for (owner, nm, desc), cls in sorted(found.items()):
    if nm in ('getTextureRegions', 'getColorBufferTexture', 'getRegions', 'getScale',
              'getSprite', 'obtain', 'getTextInput', 'setCatchBackKey', 'setCatchMenuKey', 'setScale'):
        print('%-52s %-22s %s' % (owner, nm, desc))
        print('        引用者:', ', '.join(sorted(cls)[:4]))
