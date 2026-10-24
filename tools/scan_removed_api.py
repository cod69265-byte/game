import zipfile, sys, os

jar = sys.argv[1]
z = zipfile.ZipFile(jar)

# 1.14.2 里被移除/改签名的成员
TARGETS = {
    'TextBounds':            b'BitmapFont$TextBounds',
    'BitmapFont.setScale':   b'setScale',
    'BitmapFont.getRegions': b'getRegions',
    'BitmapFont.getBounds':  b'getBounds',
    'BitmapFont.draw3':      b'draw',
    'FrameBuffer.getColorBufferTexture': b'getColorBufferTexture',
    'ParticleEffectPool':    b'ParticleEffectPool',
    'ParticleEmitter.getSprite': b'getSprite',
    'ParticleEmitter.getScale':  b'getScale',
    'Input.getTextInput':    b'getTextInput',
    'Input.setCatchBackKey': b'setCatchBackKey',
    'Input.setCatchMenuKey': b'setCatchMenuKey',
    'FreeTypeBitmapFontData':b'FreeTypeBitmapFontData',
}

hits = {k: [] for k in TARGETS}
for n in z.namelist():
    if not n.endswith('.class'):
        continue
    if n.startswith('com/badlogic/gdx/') or n.startswith('com/esotericsoftware/'):
        continue
    d = z.read(n)
    for k, t in TARGETS.items():
        if t in d:
            hits[k].append(n)

print('=== 游戏自有类中对 1.14.2 已移除 API 的引用 ===')
for k, v in hits.items():
    if not v:
        continue
    print('%-36s %2d 个类' % (k, len(v)))
    for c in sorted(v):
        print('     ', c)
