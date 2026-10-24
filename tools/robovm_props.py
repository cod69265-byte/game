"""从 RoboVM 编译器 jar 里挖出 ${...} 占位符与它们对应的注册位置。

robovm.xml / Info.plist.xml 里的 ${app.id} 这类占位符，如果名字没被注册，
编译器不会报错，只会把字面量 `${app.id}` 原样写进 Info.plist 和可执行文件名，
装到手机上会显示成一个奇怪的名字、签名也会因为 Bundle ID 不匹配而失败。
所以构建前先核对一遍合法名字。

用法：
    python tools/robovm_props.py ../maven/src110/robovm-compiler-2.3.19.jar
"""
import re
import struct
import sys
import zipfile


def constant_utf8s(data):
    if data[0:4] != b'\xca\xfe\xba\xbe':
        return []
    i = 8
    count = struct.unpack('>H', data[i:i + 2])[0]
    i += 2
    out = []
    k = 1
    while k < count:
        tag = data[i]
        i += 1
        if tag == 1:
            n = struct.unpack('>H', data[i:i + 2])[0]
            i += 2
            out.append(data[i:i + n].decode('utf-8', 'replace'))
            i += n
        elif tag in (7, 8, 16, 19, 20):
            i += 2
        elif tag == 15:
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):
            i += 8
            k += 1
        else:
            raise ValueError('bad constant pool tag %d' % tag)
        k += 1
    return out


PLACEHOLDER = re.compile(r'\$\{([A-Za-z0-9_.]+)\}')
LOOKS_LIKE_KEY = re.compile(r'^(app|robovm)\.[A-Za-z0-9_.]+$')


def main(jar):
    placeholders = {}
    keys = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if not name.endswith('.class'):
                continue
            try:
                strings = constant_utf8s(z.read(name))
            except Exception:
                continue
            for s in strings:
                for m in PLACEHOLDER.finditer(s):
                    placeholders.setdefault(m.group(1), set()).add(name)
                if LOOKS_LIKE_KEY.match(s):
                    keys.setdefault(s, set()).add(name)

    print('=== ${...} placeholders referenced in bytecode ===')
    for k in sorted(placeholders):
        where = sorted(placeholders[k])
        print('  %-20s %s' % (k, ', '.join(where[:3])))
    print('=== app.* / robovm.* literal keys ===')
    for k in sorted(keys):
        where = sorted(keys[k])
        print('  %-20s %s' % (k, ', '.join(where[:3])))


if __name__ == '__main__':
    main(sys.argv[1])
