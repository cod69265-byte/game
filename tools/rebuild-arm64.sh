#!/bin/bash
# libGDX 1.1.0 → arm64 原生库重编脚本
#
# 用途：让这版移植能在 iOS 11+ 的真机上跑。
# Java 侧不用动 —— JNI 符号名只跟方法签名有关，跟目标架构无关。
#
# 用法：在 macOS 上执行
#   chmod +x rebuild-arm64.sh && ./rebuild-arm64.sh
#
# 前置：Xcode + command line tools、ant

set -euo pipefail

WORK="${1:-$HOME/libgdx-arm64}"
mkdir -p "$WORK"
cd "$WORK"

if [ ! -d libgdx ]; then
  echo "==> 拉取 libGDX 1.1.0 源码"
  git clone --depth 1 --branch 1.1.0 https://github.com/libgdx/libgdx.git
fi

cd libgdx/gdx/jni

if [ ! -f build-ios64.xml ]; then
  echo "==> 从 build-ios32.xml 生成 build-ios64.xml"
  # The upstream file writes ${libName}.armv7 / ${libName}.386 for the thin
  # archives that lipo merges, so the suffixes are replaced rather than whole
  # file names.
  sed -e 's/-arch armv7/-arch arm64/g' \
      -e 's/-arch i386/-arch x86_64/g' \
      -e 's/iPhoneOS7\.1\.sdk/iPhoneOS.sdk/g' \
      -e 's/iPhoneSimulator7\.1\.sdk/iPhoneSimulator.sdk/g' \
      -e 's/iphoneos-version-min=5\.1/iphoneos-version-min=11.0/g' \
      -e 's/\.armv7/.arm64/g' \
      -e 's/\.386/.x86_64/g' \
      -e 's/ios32/ios64/g' \
      build-ios32.xml > build-ios64.xml
fi

echo "==> 编译"
ant -f build-ios64.xml postcompile

echo "==> 结果"
lipo -info libs/libgdx.a

echo
echo "==> 下一步：把 libgdx.a 拷回去并验证符号"
echo "  cp libs/libgdx.a <ios-port>/ios/libs/ios/libgdx.a"
echo "  python3 <ios-port>/tools/native_gap.py <ios-port>/build/native-decls.txt <ios-port>/ios/libs/ios/libgdx.a"
echo "  期望输出：demanded 121, satisfied 121, missing 0"