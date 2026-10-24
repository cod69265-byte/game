"""从 libGDX 1.14.2 的 natives jar 里提取 arm64 xcframework。

RoboVM 2.3.x 的 framework 解析规则是：
  <frameworkPaths>/<name>.framework/<name>
所以只需把 ios-arm64 切片下的 <name>.framework 目录整体复制到 ios/libs/。
模拟器切片（ios-arm64_x86_64-simulator）不参与真机构建，跳过以省体积。
"""
import os
import sys
import zipfile

SLICES = ('/ios-arm64/',)


def extract(jar, outdir):
    written = []
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not any(s in n for s in SLICES):
                continue
            if '/dSYMs/' in n:
                continue
            idx = n.find('/ios-arm64/')
            rel = n[idx + len('/ios-arm64/'):]
            if not rel or rel.endswith('/'):
                continue
            dest = os.path.join(outdir, *rel.split('/'))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(n) as src, open(dest, 'wb') as dst:
                dst.write(src.read())
            written.append((rel, os.path.getsize(dest)))
    return written


if __name__ == '__main__':
    outdir = sys.argv[1]
    total = 0
    for jar in sys.argv[2:]:
        got = extract(jar, outdir)
        print('%s -> %d files' % (os.path.basename(jar), len(got)))
        for rel, size in got:
            print('   %-70s %d' % (rel, size))
            total += size
    print('total bytes=%d' % total)
