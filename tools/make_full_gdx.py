"""合并 libGDX 1.14.2 的 gdx 与 gdx-freetype，作为 InjectShim 的输入。

InjectShim 要往 FreeTypeFontGenerator 等类上注入成员，所以两者必须在同一个
jar 里。META-INF 不参与运行，跳过可以省掉签名文件带来的重复条目。

用法：
    python tools/make_full_gdx.py ../maven/ios26/gdx-1.14.2.jar \
        ../maven/ios26/gdx-freetype-1.14.2.jar libs/gdx-1.14.2-full.jar
"""
import os
import sys
import zipfile


def merge(out_path, *inputs):
    seen = set()
    count = 0
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or '.', exist_ok=True)
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as dst:
        for src_path in inputs:
            with zipfile.ZipFile(src_path) as src:
                for n in src.namelist():
                    if n.endswith('/') or n.startswith('META-INF/'):
                        continue
                    if n in seen:
                        continue
                    seen.add(n)
                    dst.writestr(n, src.read(n))
                    count += 1
    return count


if __name__ == '__main__':
    out_path = sys.argv[-1]
    inputs = sys.argv[1:-1]
    n = merge(out_path, *inputs)
    print('merged %d entries -> %s (%d bytes)'
          % (n, out_path, os.path.getsize(out_path)))
