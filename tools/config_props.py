"""把 RoboVM 编译器 Config$Builder 里注册的属性键全部列出来。

模板里 <mainClass>${app.mainclass}</mainClass> 能不能被替换，取决于 Builder 是否
把 app.mainclass 放进 Properties。名字不在列表里就会原样输出，AOT 链接阶段会
报 "main class not found: ${app.mainclass}"。

用法：
    python tools/config_props.py ../maven/src110/robovm-compiler-2.3.19.jar
"""
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


def main(jar):
    with zipfile.ZipFile(jar) as z:
        for name in ('org/robovm/compiler/config/Config$Builder.class',
                     'org/robovm/compiler/config/Config$Properties.class',
                     'org/robovm/compiler/config/Config.class'):
            if name not in z.namelist():
                print('MISSING', name)
                continue
            strings = constant_utf8s(z.read(name))
            keys = sorted({s for s in strings
                           if s.startswith('app.') or s.startswith('robovm.')})
            print('====', name, '(%d keys)' % len(keys))
            for s in keys:
                print('   ', s)


if __name__ == '__main__':
    main(sys.argv[1])
