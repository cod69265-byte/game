# 燃烧的蔬菜3HD — iOS 26 适配版

把安卓版 `燃烧的蔬菜3HD_去运营商直连_可直接内购_全部魂石碎无限版.apk`（`com.soco.veggies3_4399` v1.5.2）移植到 **iOS 26.3**。

游戏本体一行源码都没改。做法是把"平台层"整体换掉，让原始 dex 字节码直接跑在新引擎上。

---

## 1. 为什么不能沿用老的 armv7 方案

| 事实 | 后果 |
|---|---|
| iOS 11 起系统**完全不能执行 32 位程序**（不是"商店不收"，是装上也起不来） | armv7 构建在 iOS 26 上必然闪退 |
| Xcode 14 起**移除 32 位编译能力** | 现在连编都编不出来 |
| **iOS 26 不再提供 OpenGL ES 驱动** | libGDX 的 `iosgl` 后端起不了画面，必须走 Metal |

所以 iOS 26 版本必须同时换三样东西：

```
armv7  →  arm64
OpenGL ES  →  Metal（经 ANGLE 翻译）
libGDX 1.1.0  →  libGDX 1.14.2
```

本工程同时保留两套 flavour，互不干扰：

| flavour | 架构 | 图形 | libGDX | 能跑的 iOS |
|---|---|---|---|---|
| `:ios` | armv7 | OpenGL ES | 1.1.0 | ≤ 10.3.3 |
| **`:ios26`** | **arm64** | **Metal (MetalANGLE)** | **1.14.2** | **12 … 26.x** |

---

## 2. 核心思路：不重编译游戏，只补 API 缺口

游戏字节码是 libGDX 1.1.0 编译出来的，直接扔进 1.14.2 会 `NoSuchMethodError`。有三种做法，本工程选第三种：

1. ~~反编译全部源码再重编~~ — 试过。vineflower 产出 2377 个 `.java`，其中 `GameBegin`、`SkeletonBounds`、`SocoTexture` 等 20 个文件的变量作用域和 boolean/int 混用**无法系统性修复**（dummy exception handler 反模式），CFR 更差（365 个文件里 48 个结构性损坏）。
2. ~~升级到 1.5.6 拿 arm64~~ — 1.5.6 是第一个带 arm64 的版本，但原生符号只满足 64/121，Java API 断 29 处。
3. **保留原始字节码，给 libGDX 侧注入 1.1.0 兼容成员** — 已实现，缺口归零。

`tools/src/InjectShim.java` 用 ASM 在 1.14.2 的 class 上注入 26 个成员，产出 `libs/gdx-1.14.2-ios.jar`：

| 目标类 | 注入内容 | 为什么 |
|---|---|---|
| `BitmapFont$TextBounds` | **整个新类** | 1.14.2 删了这个类，游戏有 6 个类在用 |
| `BitmapFont` | `xChars`/`capChars` 静态字段、`setScale(F)`、`getRegions()[TextureRegion`、`getBounds(CharSequence)`、`draw(Batch,CharSequence,FF)` | 1.14.2 换成 `GlyphLayout` 和 `Array` |
| `ParticleEmitter` | `getScale()`、`getSprite()` | 拆成了 `getXScale()/getYScale()` 和 `getSprites()` |
| `ParticleEffectPool` | `obtain()PooledEffect` | 泛型擦除后返回类型对不上 |
| `FrameBuffer` | `getColorBufferTexture()Texture` | 1.14.2 上移到泛型父类 `GLFrameBuffer<T>` |
| `GLTexture` | `createGLHandle()` | 1.14.2 删了静态工厂 |
| `Input` | `setCatchBackKey(Z)`、`setCatchMenuKey(Z)`、`getTextInput(3 参)` | 合并成 `setCatchKey(int,boolean)` 和 4 参 `getTextInput` |
| `PixmapPacker` | `pack(String,Pixmap)Rectangle` | 返回类型改成 `PixmapPackerRectangle` |
| `FreeType` | 5 个静态转发（`setPixelSizes`/`loadChar`/`renderGlyph`/`getCharIndex`/`getKerning`） | 1.14.2 把方法下放到 `Face`/`GlyphSlot` |
| `FreeType$Bitmap` | `getPixmap(Pixmap$Format)Pixmap` | 1.14.2 需要额外 `Color` + `scale` 参数 |
| `FreeTypeFontGenerator$FreeTypeBitmapFontData` | `spaceWidth` 字段、`regions` **数组形态**字段 | 同名字段在 1.14.2 是 `Array<TextureRegion>`，JVM 允许按描述符共存 |
| `FreeTypeFontGenerator` | `filePath` 字段、`generateData(int,String,boolean)` | 1.1.0 的便捷重载被删，游戏直接调它 |
| `utils.Os` | **整个新类** | 1.14.2 拆到独立模块，但 `UIUtils` 静态初始化还引用 |
| `utils.SharedLibraryLoader` | **整个新类** | 同上；iOS 静态链接，`load()` 做成空操作 |

**关键坑（都已修）**：
- `Array.size` 是**字段**不是方法，写成 `INVOKEVIRTUAL` 会 `StringIndexOutOfBoundsException`。
- `CHECKCAST` 的类名必须是描述符形式 `[Lcom/...;`，写成 `[com/...;` 会 `ClassFormatError: Illegal class name`。
- 有分支的方法必须 `COMPUTE_FRAMES` 而不是 `COMPUTE_MAXS`，否则 `VerifyError: Expecting a stackmap frame`。用 `COMPUTE_FRAMES` 就得自己实现 `getCommonSuperClass`（否则 ASM 会 `Class.forName` 把待注入的 jar 真的加载起来）。
- 字段去重键必须是 `name+descriptor`，只比名字会把 `regions` 的数组版本误判成"已存在"。

---

## 3. 目录结构

```
ios-port/
  build.gradle                    五个子工程：core / core26 / ios / ios26 / ios26scene
                                  （+ verifyAll / convertAudio / makeIcons / extractFrameworks / buildShim）
  settings.gradle
  core/src/main/java/             iOS ≤ 10 用的平台层（按 1.1.0 编译）
  core26/                         复用 core 源码，按 1.14.2 编译
  ios/                            armv7 + GLES（legacy，iOS ≤ 10）
    robovm.xml
    Info.plist.xml
    libs/ios/                     libgdx.a / libObjectAL.a / libgdx-freetype.a（armv7+x86）
    resources/                    1698 个游戏资源 + 30 个图标/启动图
  ios26/                          arm64 + Metal ← iOS 26.3 用这个
    src/main/java/com/soco/veggies3_4399/ios26/IOSLauncher26.java
    robovm-ios26.xml
    robovm.properties
    Info.plist.xml
    libs/ios26/                   7 个 arm64 framework（见下）
    resources/                    → junction 指向 ios/resources
  ios26scene/                     arm64 + Metal + UIScene ← iOS 27 强制要求，前向兼容
    src/                          → junction 指向 ios26/src（两个启动器共用）
    libs/                         → junction 指向 ios/libs
    resources/                    → junction 指向 ios/resources
    robovm-ios26scene.xml
    robovm.properties
    Info-scene.plist.xml
  libs/
    game-ios.jar                  原始游戏字节码（1266 class，1 处补丁：isIOS→true）
    game-core2.jar                同上但剔除 gdx（用于缺口测量）
    gdx-1.14.2-ios.jar            ← 注入 26 个成员后的产物，构建用这个
    jorbis.jar                    Ogg Vorbis 纯 Java 解码器
  tools/
    src/InjectShim.java           ASM 注入器（1.1.0 兼容层）
    src/VerifyJar.java            字节码数据流验证
    src/LinkCheck.java            常量池引用解析检查
    src/AccessCheck.java          跨 jar 成员可见性检查
    src/RuntimeContract.java      桌面 JVM 运行时契约测试
    src/SmokeTest.java            平台层冒烟测试
    src/LaunchCheck26.java        legacy 启动器 API 核对
    src/LaunchCheck26Scene.java   UIScene 启动器语义形状核对
    src/ObjCNameCheck.java        plist 里的 ObjC 类名 vs RoboVM 命名规则
    src/StaticInitOrder.java      <clinit> 里读写 Platform.platform 的类
    src/ClinitGuards.java         <clinit> 里直接调 Platform.* / Gdx.* 的类
    src/OggToWav.java             ogg → wav 转换器
    extract_frameworks.py         从 natives jar 提取 arm64 xcframework
    make_core_jar.py              生成 game-core2.jar
    make_full_gdx.py              合并 gdx + gdx-freetype（InjectShim 输入）
    make_icons.py                 生成图标/启动图
    audit_placeholders.py         核对 XML 里只用了 RoboVM 认识的 ${...}
    robovm_props.py               枚举编译器认识的占位符
    config_props.py               列出 Config$Builder 注册的属性键
    refs.py                       查 jar 内谁引用了某个类
    verify-all.ps1                一条命令跑完 13 项离线验证（gradlew verifyAll 调它）
  setupLinks.sh                   重建 resources/libs/src 三个目录链接（zip/git 会丢）
  gradlew / gradlew.bat           Gradle 7.6.4 wrapper
  ios26-libs-README.txt           每个 framework 的来源与架构
```

`ios26/libs/ios26/` 里 7 个二进制**全部是 arm64 Mach-O**（magic `0xFEEDFACF`，cputype `16777228`）：

| framework | 大小 | 来源 |
|---|---|---|
| `gdx.framework/gdx` | 251,256 | libGDX 1.14.2 natives |
| `ObjectAL.framework/ObjectAL` | 472,104 | libGDX 1.14.2 natives |
| `gdx-freetype.framework/gdx-freetype` | 735,712 | libGDX 1.14.2 natives |
| `MetalANGLEKit.framework/MetalANGLEKit` | 116,080 | MetalANGLE backend |
| `libGLESv2.framework/libGLESv2` | 4,240,888 | MetalANGLE backend |
| `libEGL.framework/libEGL` | 241,448 | MetalANGLE backend |
| `libfeature_support.framework/libfeature_support` | 251,504 | MetalANGLE backend |

**这就是 Windows 上能做出 iOS 26 版的关键**：MetalANGLE 的 jar 自带预编译 arm64 framework，不需要在本机编译任何 C/C++。

---

## 4. 构建（必须在 macOS 上）

RoboVM 的 AOT 编译器只在 macOS 上工作，Windows 上能做的是本仓库里已经做完的全部准备工作。

### 4.1 前置

```bash
# 必须 Xcode 15 或更新（iOS 26 SDK），且 Command Line Tools 指向它
xcodebuild -version          # 期望 Xcode 16/17+
sudo xcode-select -s /Applications/Xcode.app

# JDK 8（RoboVM 2.3.19 的 AOT 编译器要求）
java -version                # 期望 1.8.x

# Gradle 7.x
gradle -version
```

> **JDK 必须是 8。** MobiVM 2.3.19 的编译器用 `tools.jar` 里的接口，JDK 11+ 会直接报找不到 `com.sun.javafx`。

### 4.2 编译

```bash
cd ios-port
gradle :ios26:createIPA
```

产物：`ios26/build/robovm/Veggies3HD-iOS.ipa`

只跑模拟器：

```bash
gradle :ios26:launchIPhoneSimulator
```

### 4.3 真机

1. 打开 `ios26/signing.gradle.template`，填三个值：

```groovy
robovm {
    iosSignIdentity = "Apple Development: you@example.com (XXXXXXXXXX)"
    iosProvisioningProfile = "path/to/your.mobileprovision"
    iosSkipSigning = false
}
```

2. 把内容并入 `build.gradle` 的 `project(':ios26') { robovm { ... } }`。
3. `CFBundleIdentifier` 在 `ios26/Info.plist.xml` 里是 `${app.id}`，构建时用 `-Probovm.app.id=com.yourorg.veggies3` 覆盖，必须与 provisioning profile 的 App ID 匹配。
4. `gradle :ios26:createIPA`，用 Xcode 的 Devices and Simulators 或 Apple Configurator 装机。

首次运行要在 设置 → 通用 → VPN与设备管理 里信任开发者证书。

### 4.4 没有 Mac 时的替代路径

用 GitHub Actions 的 `macos-15` runner（自带 Xcode），把本目录推上去后跑：

```yaml
- uses: actions/checkout@v4
- uses: actions/setup-java@v4
  with: { distribution: 'zulu', java-version: '8' }
- run: cd ios-port && gradle :ios26:createIPA
- uses: actions/upload-artifact@v4
  with: { name: ipa, path: ios-port/ios26/build/robovm/*.ipa }
```

未签名 IPA 只能用模拟器或越狱设备；上真机仍需签名证书。

### 4.5 iOS 27 前向兼容：UIScene 生命周期

Apple 的 TN3187 分两阶段废弃 `UIApplicationDelegate` 单例生命周期：

| 阶段 | 行为 |
|---|---|
| **iOS 26** | 老写法照常启动，只在控制台打 `"This process does not adopt the UIScene lifecycle."` |
| **iOS 26 之后的 SDK（iOS 27）** | 直接拒绝启动：`Application failed to launch: UIScene life cycle is required for apps built with this SDK.` |

所以在 iOS 26.3 上 `:ios26` 能跑，但一旦用 iOS 27 SDK 重编就会起不来。为此并存了 `:ios26scene`：

```bash
gradle :ios26scene:createIPA      # 产物 ios26scene/build/robovm/*.ipa
```

两个 flavour 的区别只有三处：

| | `:ios26` | `:ios26scene` |
|---|---|---|
| 启动器 | `IOSLauncher26` | `IOSLauncher26Scene` |
| plist | 无 `UIApplicationSceneManifest` | 有，指向 `VegSceneDelegate` |
| 生命周期 | `UIApplicationDelegate` | `UIApplicationDelegate` + `UISceneDelegate` |

**为什么不用 fork libGDX**：`IOSApplication.didFinishLaunching` 是 `final`，第 70–72 行自己 `new UIWindow(bounds)` 并 `makeKeyAndVisible()`，第 86 行 `setRootViewController`。UIKit 的调用顺序是「先 `didFinishLaunchingWithOptions:`，后 `scene:willConnectToSession:options:`」，也就是说 libGDX 在第一个回调里已经把 GL/audio/input 全部初始化完、窗口也建好了。scene 回调里只需 `window.setWindowScene(scene)` 把它挂到场景上即可，**libGDX 一行都不用改**。

`IOSLauncher26Scene` 的三段：

```java
createApplication()                                  // 配置 + new IOSApplication(...)，存进 static app
getConfigurationForConnectingSceneSession(...)       // 返回 UISceneConfiguration，绑定 VegSceneDelegate
VegSceneDelegate.willConnect(scene, session, opts)   // app.getUIWindow().setWindowScene((UIWindowScene) scene)
```

**这里有一个只在真机上才会暴露的坑**：plist 里的 `UISceneDelegateClassName` 必须是 RoboVM 生成的 ObjC 类名，规则见 `ObjCMemberPlugin.getCustomClassName()` —— `"j_" + 类名.replace('.', '_')`，**只替换点号，内层类的 `$` 原样保留**：

```
com.soco.veggies3_4399.ios26.scene.IOSLauncher26Scene$VegSceneDelegate
  → j_com_soco_veggies3_4399_ios26_scene_IOSLauncher26Scene$VegSceneDelegate
```

写错这个名字的后果是**完全静默的**：UIKit 找不到类 → 不调 `willConnect` → 窗口永不挂上 scene → App 黑屏，控制台无任何报错，编译期和链接期也都查不出来。`tools/ObjCNameCheck.java` 就是专门核对这一条的（它模拟 RoboVM 的命名逻辑并沿继承链判断 `isNSObject()`）。

---

## 5. 已做的验证

全部在 Windows 上离线跑完。**一条命令复现全部 13 项**：

```
$ ./gradlew verifyAll          # 或 powershell -File tools/verify-all.ps1
ok   VerifyJar shim         verified=1179 problems=0 total=1179
ok   LinkCheck game         classes=470 unresolved=0 affectedClasses=0
ok   VerifyJar core26       verified=8 problems=0 total=8
ok   LinkCheck core26       classes=8 unresolved=0 affectedClasses=0
ok   VerifyJar ios26        verified=3 problems=0 total=3
ok   LinkCheck ios26        classes=3 unresolved=0 affectedClasses=0
ok   AccessCheck game       refs=64828 violations=0
ok   AccessCheck ios26      refs=69 violations=0
ok   RuntimeContract        RUNTIME CONTRACT PASSED
ok   SmokeTest              SMOKE TEST PASSED
ok   LaunchCheck26          LAUNCH CHECK PASSED
ok   LaunchCheck26Scene     LAUNCH CHECK PASSED
ok   ObjCNameCheck          classes=4478 custom=0 checked=1 mismatches=0
ALL CHECKS PASSED
```

`verify-all.ps1` 会先按 1.14.2 重编 `core26` 与 `ios26` 两个源集、再重编工具、再逐项跑检查，
所以它测的是当前源码，不是上一次的产物。默认路径全部从脚本位置推导 —— 工程路径含中文，
而 Windows PowerShell 5.1 会把无 BOM 的 UTF-8 脚本按 ANSI 解码，写死中文字面量会变乱码。

下面各小节是逐项的原始命令与结果（`$j25` = JDK 25 的 `java`，`$asmcp` = ASM 9.5 五个 jar）。

### 5.1 游戏 → libGDX 1.14.2 的 API 缺口

```
$ java -cp ... LinkCheck libs/game-core2.jar libs/gdx-1.14.2-ios.jar
classes=470 unresolved=0 affectedClasses=0
```

作为对照：

| libGDX | unresolved | affectedClasses |
|---|---|---|
| 1.1.0 | 0 | 0 |
| 1.9.14 | 33 | 9 |
| **1.14.2 原生** | **34** | **13** |
| **1.14.2 + 注入** | **0** | **0** |

### 5.2 注入后的字节码是否合法

```
$ java -cp ... VerifyJar libs/gdx-1.14.2-ios.jar
verified=1179 problems=0 total=1179
```

### 5.3 平台层与启动器

```
$ java -cp ... LinkCheck build/classes-core26.jar libs/gdx-1.14.2-ios.jar libs/game-core2.jar
classes=8 unresolved=0 affectedClasses=0

$ java -cp ... LinkCheck build/classes-ios26.jar <full cp>
classes=3 unresolved=0 affectedClasses=0

$ java -cp ... VerifyJar build/classes-core26.jar <cp>
verified=8 problems=0 total=8

$ java -cp ... VerifyJar build/classes-ios26.jar <cp>
verified=3 problems=0 total=3

$ java -cp ... AccessCheck build/classes-ios26.jar <cp>
refs=69 violations=0
```

> `<full cp>` 必须写成**每个 jar 一个独立参数**：`LinkCheck`/`VerifyJar`/`AccessCheck` 按位置收集参数，
> 把 `a.jar;b.jar` 塞进一个字符串会被当成单个文件路径，报一串假的 `ClassNotFoundException`。

### 5.3a 可见性与静态初始化顺序

`LinkCheck` 只证明"成员在"，不证明"调用方有权限"；`AccessCheck` 补这一层（`SocoTexture`/`SocoFreeTypeFontGenerator` 就在 `com.badlogic.gdx.graphics` 包下靠同包关系访问 `GLTexture.glHandle`、`Texture.managedTextures`，1.14.2 若收紧可见性，编译期查不出、真机抛 `IllegalAccessError`）：

```
$ java -cp ... AccessCheck libs/game-core2.jar <全运行时 cp>
refs=64828 violations=0
```

`StaticInitOrder` 查有没有类在 `<clinit>` 里读写 `Platform.platform`（唯一赋值点是 `SocoMain.<init>`，早于它读就 NPE）：

```
clinitReaders=0 clinitCallers=28 writers=1
```

`ClinitGuards` 查有没有类在 `<clinit>` 里直接调 `Platform.*` 或访问 `Gdx.*`（未就绪时 `invokevirtual` 在解析接收者时 NPE，栈里只指向那个类的 `<clinit>`，看不出谁触发的）：

```
platformClinit=0 gdxClinit=0
```

### 5.3b 构建配置自检

RoboVM 2.3.19 只注册三个占位符（`app.executable` / `app.id` / `app.name`，来自
`Config$Builder`），其余 `${...}` 会被**原样**写进产物。`tools/audit_placeholders.py`
把 XML 注释剔掉后核对：

```
ios/robovm.xml               found=[]  INVALID=none
ios/Info.plist.xml           found=['app.executable', 'app.id']  INVALID=none
ios26/robovm-ios26.xml       found=[]  INVALID=none
ios26/Info.plist.xml         found=['app.executable', 'app.id', 'app.name']  INVALID=none
```

原本两处会静默出错，已修：

| 位置 | 原值 | 后果 | 现为 |
|---|---|---|---|
| 两个 `Info.plist.xml` | `${app.version}` | 未注册，`CFBundleVersion` 变成字面量 | `1.5.2` |
| 两个 `robovm.xml` | `<mainClass>${app.mainclass}</mainClass>` | AOT 链接报 `main class not found: ${app.mainclass}` | 硬编码全限定类名 |

`app.id` / `app.name` / `app.executable` 由 `ios/robovm.properties` 与
`ios26/robovm.properties` 提供（`Config$Builder` 依次读
`robovm.properties` → `robovm.local.properties` → `robovm.test.properties`）。

`tools/robovm_props.py` 用来枚举编译器认识的占位符，`tools/config_props.py`
列出 `Config$Builder` 注册的键，两者配合可在换 RoboVM 版本后重新核对。

### 5.4 运行时契约（19 项，全部 ok）

```
$ java -cp ... RuntimeContract
ok   PLATFORM_IOS constant = 3
ok   getPaltForm = 3
ok   getPlatform = 3
ok   getSaveDataName = FireVeg201406
ok   useWavRepaceOgg = true
ok   TextBounds copy.width = 12.5
ok   TextBounds copy.height = 34.5
ok   BitmapFont.setScale declared by BitmapFont = true
ok   Os.IOS present = true
ok   SharedLibraryLoader.os == Os.IOS = true
ok   UIUtils.isIos = true
ok   UIUtils.isAndroid = false
ok   V3Main(Platform) public = true
ok   Platform.platform is bound = true
ok   FontUtil.DEFAULT_CHARS non-empty = true
ok   FontUtil.useHiero (skips FreeType path) = true
ok   loadGame declared by IOSPlatForm = true
ok   saveGame declared by IOSPlatForm = true
ok   AudioUtil.getKey rewrites .ogg = Audio/Music/bg.wav
RUNTIME CONTRACT PASSED
```

其中 `UIUtils.isIos = true` 尤其关键：它证明重建的 `Os`/`SharedLibraryLoader` 语义正确，`scene2d` 不会在静态初始化时 `NoClassDefFoundError`。

### 5.5 平台层冒烟（23 个 iOS 商品 id）

```
$ java -cp ... SmokeTest
...
ok   pay orderId echoed = ORDER-42
ios product ids routed: 23
SMOKE TEST PASSED
```

### 5.5b 启动器语义形状

`LinkCheck` 证明成员存在，`VerifyJar` 证明字节码合法，都不证明**接线形状**对。两个启动器各有一个专项检查：

```
$ java -cp ... LaunchCheck26
LAUNCH CHECK PASSED          # 24 项：配置字段类型、MetalANGLE 枚举值、Delegate 抽象方法

$ java -cp ... LaunchCheck26Scene
checks=26 failed=0
LAUNCH CHECK PASSED
```

`LaunchCheck26Scene` 的 26 项里有几项是别的手段查不到的：

- `getConfigurationForConnectingSceneSession(UIApplication, UISceneSession, UISceneConnectionOptions)` 必须是**真覆写**——签名精确、`getDeclaringClass()==launcher`、且父类 `UIApplicationDelegateAdapter` 有同签名。UIKit 按选择器匹配，参数类型差一个就永远匹配不上，静默退回 legacy 路径。
- `VegSceneDelegate` 必须 `extends UIWindowSceneDelegateAdapter` 而非 `UISceneDelegateAdapter`——只有前者带 `window`/`setWindow`。
- `VegSceneDelegate` 必须是 `static`——非静态内部类会隐式持有外层实例，RoboVM 对它的 ObjC 类注册行为不同。
- `UISceneSessionRole.Application` 必须存在——取错角色字符串会匹配不到 plist 里的 `UIWindowSceneSessionRoleApplication`。

### 5.5c plist 里的 ObjC 类名

```
$ java -cp ... ObjCNameCheck <绑定 jar> ios26scene/Info-scene.plist.xml ios26/Info.plist.xml
ok   Info-scene.plist.xml -> j_com_soco_veggies3_4399_ios26_scene_IOSLauncher26Scene$VegSceneDelegate  (com/soco/veggies3_4399/ios26/scene/IOSLauncher26Scene$VegSceneDelegate)
info Info.plist.xml: no UISceneDelegateClassName (legacy UIApplicationDelegate lifecycle)
classes=4478 custom=0 checked=1 mismatches=0
```

必须把 RoboVM 绑定 jar 一起传进去，否则继承链一出被测 jar 就断，`isNSObject()` 判定失败（首版 `classes=0` 就是这个原因）。

### 5.6 资源与音频对账

```
APK 保留资源 1586 个 → iOS bundle 0 缺失
99 个被引用的 .ogg → 99 个 .wav 在位（102 个全转，failed=0，26,700,700 B）
```

---

## 6. 平台层改了什么

`core/src/main/java/com/soco/veggies3_4399/IOSPlatForm.java`（`extends Platform`）：

| 方法 | 返回值 | 理由 |
|---|---|---|
| `getPaltForm()` | `3` (`PLATFORM_IOS`) | 游戏里已有 iOS 分支，`SocoMain.render()` 用 `==3` 决定 `ChangeModule` 而非 `ResetToRunModule` |
| `getPlatform()` | `3` | `FontUtil` 和 `InputLabel` 用它切 iOS 字体基线偏移、关掉系统输入框 |
| `getSaveDataName()` | `"FireVeg201406"` | 与安卓一致，存档可互通 |
| `loadGame/saveGame` | `false` | 返回 false 才会落到 `SaveData` 的 `Gdx.files.local()` 通用路径 —— 这条路径在 iOS 上本来就可用，不用重写存档格式 |
| `useWavRepaceOgg()` | `true` | `AudioUtil.getKey()` 据此把 `.ogg` 换成 `.wav` |
| `pay(...)` 四个重载 | 直接 `payNotify(true, orderId)` | 保持"可直接内购"的破解行为 |
| `isPriceEnable` | `true` | 不过滤任何价位 |
| `isRewardEnable` | `Config.bLibao` | 与安卓一致 |
| `getPrizeList(String)` | 16 张 reword 表 | 从 `AndroidPlatForm` 原样搬 |
| `inputRewardKey` | `Gdx.input.getTextInput` + 本地码表 | 安卓用 `AlertDialog` + `SharedPreferences`，这里换 `Preferences` |
| `getServerTime` | `SynTime.getTime()` | 服务器时间校准 |
| `isDanji()` | `true` | 单机模式，不发联网请求 |

`GameData.isIOS` 和 `UI_chongzhi.isIOS` 都设为 `true`，让游戏走它自带的 iOS 价格表和商品 ID（19–34、42–48，共 23 个）。

音频：iOS 的 ObjectAL 底层是 AVFoundation，**不解码 Ogg Vorbis**。用游戏自带的 JOrbis 写了 `OggToWav`，102 个 ogg 全转成 16-bit PCM WAV。

---

## 7. 已知限制

1. **必须 macOS + JDK 8 + Xcode 15+ 才能出 IPA。** Windows 上无法执行 RoboVM 的 AOT 编译，这是工具链限制，不是工程缺陷。
2. **未在真机上验证。** 所有验证都是字节码级 + 桌面 JVM 级。真机上可能出现的问题：MetalANGLE 对某些 GLES2 扩展的实现差异、Spine 二进制 `.skel` 版本兼容、中文字体的 FreeType 路径（`FontUtil.useHiero=true` 时该路径被跳过，所以风险低）。
3. **`MinimumOSVersion` 设为 12.0。** libGDX 1.14.2 的 Metal 后端最低支持 iOS 12；如果你的设备是 iOS 26.3，这个值可以按需上调。
4. **网络相关功能在 iOS 上会失败**：原游戏的 `socogame.com` 服务器多数已下线，`SynTime.refresh()` 会超时（已把 `DEBUG_MODE` 从 `true` 改 `false`，避免连内网测试地址 `192.168.2.87`）。游戏本身是单机，不影响游玩。
5. **`libGDX 1.14.2` 的 `Preferences.putXxx` 返回类型从 `void` 变成 `Preferences`**，所以 `core` 源码要按 1.14.2 重编一次（`core26` 工程就是干这个的）。
6. **RoboVM 2.3.19 的占位符只有三个**（`app.executable`/`app.id`/`app.name`），别写 `${app.version}`、`${app.mainclass}`，它们会原样进产物且不报错。
7. **`Os` 与 `SharedLibraryLoader` 在 libGDX 1.14.2 主 jar 里不存在**（拆去了 `gdx-jnigen-loader`，而 Maven Central 上该 artifact 404）。已由 `InjectShim` 重建：`UIUtils.<clinit>` 要读 `SharedLibraryLoader.os` 比对 `Os.IOS`，缺了会在 scene2d 首次初始化时 `NoClassDefFoundError`。
8. **`libGDX 1.14.2` 的 natives 不再是 `.a`**，改为 `gdx.xcframework/`；`extract_frameworks.py` 必须按 `'/ios-arm64/'` 过滤，按 `'/ios/'` 会把 tvOS 切片一起带进来。9. **构建链在 Windows 上实测到第 7 步**（见 `INSTALL.md` 的证据表）：`gradlew projects`、`compileJava` 全部通过；`createIPA` 一路跑到 `Config.build()` → `IOSTarget.init()` 成功（说明我写的 robovm XML / plist / framework 路径 / mainClass 全部被 RoboVM 接受），最后停在 `UnsatisfiedLinkError: Native library for windows-x86_64 not found` —— RoboVM 的 AOT 编译器本体没有 Windows 版。配置层面已无未知风险。
10. **`configFile` 必须传绝对路径字符串。** `RoboVMPluginExtension.setConfigFile` 只接受 `String`（传 `File` 会 `Value is null`），而插件内部 `new File(str).exists()` 是相对 **JVM 工作目录**（根工程目录）解析的，写 `'robovm.xml'` 会报 `Invalid 'configFile' specified`。三处都已改成 `new File(projectDir, '...').absolutePath`。
11. **`mainClassName` 必须挂在 `ext` 上。** 插件用 `project.hasProperty("mainClassName")` 判断，而纯 `java` 工程没有这个属性（只有 `application` 插件注册），直接写 `mainClassName = '...'` 会报 `Could not set unknown property`。三处都已改成 `ext.mainClassName = '...'`。
12. **`AccessCheck` 必须先索引 client 自身的成员，再索引依赖 jar。** 否则常量池里的 `SocoTexture.addManagedTexture`（public）会被沿继承链解析到父类 `Texture.addManagedTexture`（private），产生假越权。同理 `robovm-rt` 打包了 JDK 类库，`java/lang/Enum`、`java/util/TimerTask` 的 protected 构造器需要 client 的父类边才能判对 —— 由 `loadHierarchy(client)` 补上。

---

## 8. 参考

- [Apple: 32 位 App 停止支持](https://developer.apple.com/news/upcoming-requirements/?id=06062022a)
- [Apple TN3187: Migrating to the UIKit scene-based life cycle](https://developer.apple.com/documentation/technotes/tn3187-migrating-to-the-uikit-scene-based-life-cycle)
- [libGDX 1.12.0 发布说明（MetalANGLE 后端）](https://libgdx.com/news/2023/07/gdx-1-12)
- [MetalANGLE](https://github.com/kakashidinho/metalangle)
- [MobiVM（RoboVM 维护分支）](https://github.com/MobiVM/robovm)
