"""列出 jar 内 class 引用的、匹配给定正则的类名。"""
import re
import sys
import zipfile


def refs(jar, pattern):
    rx = re.compile(pattern.encode())
    out = {}
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not n.endswith('.class'):
                continue
            body = z.read(n)
            hits = sorted({m.decode('utf-8', 'replace') for m in rx.findall(body)})
            if hits:
                out[n] = hits
    return out


if __name__ == '__main__':
    jar = sys.argv[1]
    pattern = sys.argv[2]
    res = refs(jar, pattern)
    print('files=%d' % len(res))
    for k in sorted(res):
        print('  %s -> %s' % (k, ', '.join(res[k])))
