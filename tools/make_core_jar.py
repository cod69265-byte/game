import zipfile, sys, os

src = sys.argv[1]      # game-ios.jar
dst = sys.argv[2]      # game-core2.jar

# 保留游戏自己的 gdx 子类（它们不在官方 gdx jar 里），其余 com/badlogic/gdx 剔除
KEEP_PREFIX = (
    'com/badlogic/gdx/graphics/SocoTexture',
    'com/badlogic/gdx/graphics/g2d/freetype/SocoFreeTypeFontGenerator',
)
DROP_PREFIX = (
    'com/badlogic/gdx/',
)

zin = zipfile.ZipFile(src)
kept = dropped = 0
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for n in zin.namelist():
        if n.endswith('/'):
            continue
        if n.startswith(DROP_PREFIX) and not n.startswith(KEEP_PREFIX):
            dropped += 1
            continue
        zout.writestr(n, zin.read(n))
        kept += 1

print('kept=%d dropped=%d size=%d -> %s' % (kept, dropped, os.path.getsize(dst), dst))
