#!/bin/bash
# 把工程切到 arm64（iOS 11+ 真机）模式。
#
# 前提：已用 rebuild-arm64.sh 编出含 arm64 的 libgdx.a 并放到 ios/libs/ios/
#
# 用法：./switch-to-arm64.sh [ios-port 目录]

set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$ROOT"

echo "==> 检查原生库架构"
for f in ios/libs/ios/libgdx.a ios/libs/ios/libObjectAL.a; do
  if [ -f "$f" ]; then
    printf '  %-28s ' "$f"
    lipo -info "$f" 2>/dev/null || echo "(lipo 不可用)"
  else
    echo "  缺少 $f"
  fi
done

echo
echo "==> 改写 robovm.xml"
sed -i '' 's|<arch>thumbv7</arch>|<arch>arm64</arch>|' ios/robovm.xml 2>/dev/null \
  || sed -i 's|<arch>thumbv7</arch>|<arch>arm64</arch>|' ios/robovm.xml

echo "==> 改写 build.gradle"
sed -i '' "s|archs 'thumbv7'|archs 'arm64'|" build.gradle 2>/dev/null \
  || sed -i "s|archs 'thumbv7'|archs 'arm64'|" build.gradle

echo "==> 完成，现在可以构建"
grep -n 'arch' ios/robovm.xml build.gradle | head -20