ios26/libs/ios26 里的二进制全部来自 Maven Central 上的预编译产物，本机未编译任何 C/C++。
每个文件都用 Mach-O 头校验过：magic 0xFEEDFACF，cputype 16777228（arm64），cpusubtype 0。

framework                     size        arch   来源
---------------------------------------------------------------------------------------------
gdx.framework/gdx             251,256     arm64  com.badlogicgames.gdx:gdx-platform:1.14.2:natives-ios
ObjectAL.framework/ObjectAL   472,104     arm64  同上
gdx-freetype.framework/
  gdx-freetype                735,712     arm64  com.badlogicgames.gdx:gdx-freetype-platform:1.14.2:natives-ios
MetalANGLEKit.framework/
  MetalANGLEKit               116,080     arm64  com.badlogicgames.gdx:gdx-backend-robovm-metalangle:1.14.2
libGLESv2.framework/
  libGLESv2                 4,240,888     arm64  同上
libEGL.framework/libEGL       241,448     arm64  同上
libfeature_support.framework/
  libfeature_support          251,504     arm64  同上

提取方式
--------
python tools/extract_frameworks.py ios/libs/ios26 \
    ../maven/ios26/gdx-platform-1.14.2-natives-ios.jar \
    ../maven/ios26/gdx-freetype-platform-1.14.2-natives-ios.jar \
    ../maven/ios26/gdx-backend-robovm-metalangle-1.14.2.jar

1.14.2 的 natives jar 布局与 1.1.0 不同：1.1.0 是 META-INF/robovm/ios/libs/libgdx.a，
1.14.2 改成了 xcframework：

  META-INF/robovm/ios/libs/<name>.xcframework/
      ios-arm64/<name>.framework/<name>              <- 真机切片，只要这个
      ios-arm64_x86_64-simulator/<name>.framework/   <- 模拟器切片，跳过
      dSYMs/                                          <- 调试符号，跳过

所以脚本按 '/ios-arm64/' 过滤，不能用 '/ios/'（那样会把 tvOS 也带进来）。

RoboVM 的解析规则
-----------------
robovm-ios26.xml 里写：

  <frameworkPaths><path>libs/ios26</path></frameworkPaths>
  <frameworks><framework>gdx</framework>...</frameworks>

RoboVM 会去找 libs/ios26/gdx.framework/gdx，正好是上面的目录结构。
注意 <frameworkPaths> 是相对 ios26/（子工程目录）解析的，不是相对 ios26/libs/。

注意事项
--------
1. 这些 framework 只有 arm64，没有 armv7。想同时出 legacy 版本必须用 ios/libs/ios 下
   的三个 .a（armv7+x86），两者不能混用。
2. libGLESv2 有 4 MB，是 ANGLE 的 GLES 实现本体；libEGL 是它的 EGL 胶水层；
   MetalANGLEKit 把 CAMetalLayer 包成 MGLKView。三者缺一不可。
3. 别删 Headers/ 和 Modules/。RoboVM 不读它们，但 MetalANGLEKit.framework 的
   module.modulemap 被 ANGLE 自己的头文件引用链依赖，Xcode 链接阶段会校验目录完整性。
