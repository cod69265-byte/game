# 燃烧的蔬菜3HD — iOS 适配版

把安卓版 `燃烧的蔬菜3HD_去运营商直连_可直接内购_全部魂石碎无限版.apk`（`com.soco.veggies3_4399` v1.5.2）移植到 iOS。

**移植策略：不重写游戏，只换平台层。**

游戏本体是 libGDX 1.1.0 + 自研 `com.soco` 框架，dex 里的 `com/soco` 核心逻辑几乎零 Android 依赖 —— 全部平台差异收敛在一个抽象类 `com.soco.util.platform.Platform` 和它的唯一子类 `AndroidPlatForm` 上。所以这里的做法是：

1. 原始 `classes.dex` 直接转成 `libs/game-ios.jar` 复用（**只有 1 处字节码补丁**）；
2. 删掉 Android 渠道 SDK 和 Android 平台层；
3. 手写 `IOSPlatForm`（新 Platform 子类）+ `IOSLauncher`（RoboVM 入口）；
4. 音频 ogg → wav（iOS 的 ObjectAL 不解码 Ogg Vorbis）。

---

## 目录

```
ios-port/
  build.gradle              构建脚本（RoboVM / MobiVM 2.3.19）
  settings.gradle
  core/src/main/java/       平台层（5 个手写文件）
    com/soco/veggies3_4399/IOSPlatForm.java
    com/soco/sdk/servertime/{ConnectListener,HttpConnect,SocoServerTime}.java
    com/soco/technology/SynTime.java
  ios/
    src/main/java/com/soco/veggies3_4399/ios/IOSLauncher.java
    robovm.xml              AOT 链接配置
    Info.plist.xml          Bundle 配置
    signing.gradle.template 真机签名配置模板（填 p12 + mobileprovision）
    libs/ios/               libgdx.a / libObjectAL.a / libgdx-freetype.a（armv7 + x86）
    resources/              1698 个资源 + 30 个图标/启动图（63.7 MB）
  libs/
    game-ios.jar            原始游戏字节码（1266 class，1 处补丁）
    game-core.jar           同上但剔除 com/badlogic/gdx（用于 API 兼容性测量）
    jorbis.jar              Ogg Vorbis 纯 Java 解码器（离线转换工具用）
  tools/
    src/OggToWav.java       ogg → wav 转换器
    src/VerifyJar.java      字节码链接/数据流验证
    src/LinkCheck.java      常量池引用解析检查
    src/NativeDecls.java    列出 Java 侧 native 方法 → JNI 符号名
    src/SmokeTest.java      平台层冒烟测试（桌面 JVM 跑）
    make_icons.py           生成 iOS 图标/启动图
    compare_natives.py      libgdx.a 导出的 JNI 符号
    native_gap.py           Java 需求 vs 原生导出 的差集
    rebuild-arm64.sh        【macOS】把 libGDX 1.1.0 原生库重编成 arm64
    switch-to-arm64.sh      【macOS】把工程配置切到 arm64
```

---

## 关键改动

### 1. 字节码补丁（唯一一处）

`com/soco/ui/UI_chongzhi.class` 构造函数里的 `this.isIOS = false`：

```
iconst_0 → iconst_1        ; 03 b5 00 c3  →  04 b5 00 c3
```

改完 `UI_chongzhi` 的 iOS 商品表分支生效：`GOLDPAYINDEX_IOS = {19,20,21,22,23}`、`GEMPAYINDEX_IOS = {29,30,24,25,26,27,28}`，走 `coin_num_ios / coin_price_ios / dia_num_ios / dia_price_ios` 那套已经排好的 iOS 定价。

游戏源码里本来就有 `GameData.isIOS` 和完整的 iOS 商品 id 常量（`com.soco.game.iap.iapConfig`：19–34、42–48），只是安卓包里硬编码成 `false` 关掉了。

### 2. `IOSPlatForm`（新写的 Platform 子类）

| 方法 | 返回值 | 理由 |
|---|---|---|
| `getPaltForm()` | **3** (`PLATFORM_IOS`) | `SocoMain.render()` 里 `==3` 走 `GameManager.ChangeModule` |
| `getPlatform()` | **3** | `FontUtil` / `InputLabel` 靠它选 iOS 字体基线与输入框路径 |
| `getSystemLang()` | `"zh"` | 中文资源 |
| `getSaveDataName()` | `"FireVeg201406"` | 与安卓一致，存档可互通 |
| `loadGame` / `saveGame` | **`false`** | 返回 false 才会走 `SaveData` 里 `Gdx.files.local()` 的通用实现 |
| `useWavRepaceOgg()` | **`true`** | 让 `AudioUtil.getKey` 把 `.ogg` 换成 `.wav` |
| `pay(...)` 四个重载 | `payNotify(true, orderId)` | 保持原包的「内购直通」行为 |
| `getPrizeList(String)` | 16 张奖励表 | 与 `AndroidPlatForm` 逐值一致 |
| `inputRewardKey(...)` | `Gdx.input.getTextInput` | 特权码改用 libGDX 输入框 + `Preferences` 记已用码 |

`isDanji()` 返回 `true`、`updateFromNet()` 返回 `false`、`isChannelExit()` 返回 `false` —— 这版本来就不连运营商计费。

### 3. 音频：102 个 ogg 全部转 wav

iOS 后端 `IOSSound` → `OALSimpleAudio.preloadEffect()` → OpenAL，**不解码 Ogg Vorbis**。安卓能放是因为 libGDX 的 Android 后端带了 ogg 解码。

没有 ffmpeg，所以用游戏自己打包的 JOrbis（`com.jcraft.jorbis` 45 个类）写了个离线转换器：

```
java -cp build/classes-tools;libs/jorbis.jar OggToWav <ogg目录> <wav目录>
```

结果：`converted=102 failed=0 bytes=26,700,700`。输出 16-bit PCM WAV。

### 4. 删掉的东西

| 类别 | 内容 |
|---|---|
| 渠道 SDK | `cn.m4399.*`、`cn.egame.*`、`com.alipay.*`、`com.unicom.*`、`com.tendcloud.*`、`com.ta.utdid2.*` |
| Android 平台层 | `MainActivity`、`MyApplication`、`AndroidPlatForm`、`ActFlashPlay`、`GameRecord`、`R`、`BuildConfig` |
| 支付 | `Payment*`、`IapConfig`、`soco.sdk.billingstat.*` |
| 死代码 | `com.soco.lua.*` + `org.luaj.*`（251 个类，grep 确认零外部引用） |
| 资源 | `CHANNEL/ CMGC/ edfile/ egame/ UnicomConsume/ unipaysdk_res/` + 各类加密支付配置 + `.so` |

`com/soco` 从 266 个类缩到 5 个手写文件，其余全部来自 `game-ios.jar`。

---

## 架构决策：为什么是 armv7 而不是 arm64

这是本次移植**唯一需要你决策的取舍**，我做了完整测量：

**Java API 兼容性**（`LinkCheck`，466 个游戏类 × 11 个 libGDX 版本）：

| libGDX | 未解析引用 | 受影响类 |
|---|---|---|
| 1.1.0（游戏原始版本） | **0** | 0 |
| 1.2.0 – 1.4.1 | 23 | 3 |
| 1.5.6 | 29 | 6 |
| 1.6.5 – 1.9.5 | 30 | 7 |
| 1.9.10 – 1.9.14 | 33 | 9 |

**原生符号满足度**（`NativeDecls` + `native_gap.py`，Java 侧 121 个 native 方法）：

| libGDX | JNI 符号满足 | 架构 |
|---|---|---|
| 1.1.0 | **121/121** | armv7 + x86（**无 arm64**） |
| 1.6.5 | 64/121 | armv7 + arm64 + x86 + x86_64 |
| 1.9.14 | 106/121 | armv7 + arm64 + x86 + x86_64 |

**arm64 从 libGDX 1.5.6 才开始有**（2016 年）。而一旦升上去：

- Java 侧断 30 处：`BitmapFont$TextBounds` 被 `GlyphLayout` 取代、`BitmapFont.setScale(float)` / `getBounds(CharSequence)` / `getRegions()` 移除、`Input.getTextInput` 签名变化、`ParticleEmitter.getSprite/getScale` 移除、`FrameBuffer.getColorBufferTexture` 返回类型变化…… 这些是 `FontUtil`、`CCLabel`、`TextBox`、`InputLabel`、`ResourceManager`、`GameManager` 直接调的。
- 原生侧还缺 15 个 JNI 符号（`Matrix4.det/inv/mul`、`FreeType.*`）—— `Matrix4` 是 libGDX 每帧相机运算的核心，缺了必崩。

**所以：1.1.0 是唯一「Java 与原生两侧都对得上」的版本，代价是只能出 armv7。**

**armv7 的硬限制：iOS 11 起系统完全不能运行 32 位 App**（不只是 App Store 不收，是装上也起不来，点开直接闪退）。所以真机必须满足其一：

- 设备停在 **iOS ≤ 10.3.3**（iPhone 4S / 5 / 5c、iPad 2 / 3 / 4、iPod touch 5 的官方上限就是 10.3.3）；或
- 自己把 libGDX 1.1.0 的原生库按 arm64 重编一遍（C 源码是架构中立的，见下文「真机运行」一节）。

当前 `robovm.xml` 里 `<arch>thumbv7</arch>` 对应第一条路。

---

## 构建

需要 macOS + Xcode（RoboVM 编译器只在 macOS 上工作，本机是 Windows，所以这里只做了 javac 级验证）。真机的完整步骤见下一节「真机运行」，这里只列可用任务。

RoboVM 2.3.19 Gradle 插件提供的任务（已从插件 jar 里核对过名字）：

| 任务 | 作用 |
|---|---|
| `ios:createIPA` | 产出 `.ipa`（`robovmArchive` 的别名） |
| `ios:robovmArchive` | 编译 + 归档到 `ios/build/robovm/` |
| `ios:robovmInstall` | 编译 + 安装到设备，不启动 |
| `ios:launchIOSDevice` | 编译 + 安装 + 在真机上启动 |
| `ios:launchIPhoneSimulator` / `ios:launchIPadSimulator` | 模拟器 |
| `ios:launchConsole` | 控制台输出模式 |

`build.gradle` 里当前是 `iosSkipSigning = true`，先确认能编译过再处理签名（`ios/signing.gradle.template` 是可直接用的签名段）。

依赖全部离线可用：`../maven/repo/` 是标准 Maven 仓库布局（8 个 jar + pom 骨架），`build.gradle` 里 `maven { url "${rootDir}/../maven/repo" }` 排在最前面，只有 RoboVM Gradle 插件本身需要联网拉一次。

离线辅助任务（Windows 上就能跑）：

```bash
gradle convertAudio    # 重新生成 wav
gradle makeIcons       # 重新生成图标/启动图
```

### 不用 Gradle 的验证方式

本机所有验证都是直接调 javac/java 完成的，脚本在 `build/` 里留了日志：

```powershell
$jc = "C:\Program Files\JetBrains\PyCharm 2026.2.3\jbr\bin\javac.exe"
$mvn = "..\maven"
$cp = "libs\game-ios.jar;$mvn\gdx-1.1.0.jar;$mvn\gdx-freetype-1.1.0.jar;$mvn\gdx-backend-robovm-1.1.0.jar;$mvn\robovm\robovm-rt-2.3.19.jar;$mvn\robovm\robovm-objc-2.3.19.jar;$mvn\robovm\robovm-cocoatouch-2.3.19.jar"

# 平台层
& $jc -encoding UTF-8 -source 8 -target 8 -cp $cp -d build\classes-core (Get-ChildItem core\src -Recurse -Filter *.java)
# RoboVM 入口
& $jc -encoding UTF-8 -source 8 -target 8 -cp "$cp;build\classes-core" -d build\classes-ios (Get-ChildItem ios\src -Recurse -Filter *.java)
```

注意 javac 的 `@argfile` 必须是 **UTF-8 无 BOM**，带 BOM 会报「无效文件名」。

---

## 验证结果

本机（Windows，无 iOS 工具链）能做的静态验证全部通过：

```
VerifyJar  game-ios.jar   verified=1266  problems=0
LinkCheck  game-ios.jar   classes=1266   unresolved=0     (对 libGDX 1.1.0)
LinkCheck  game-core.jar  classes=466    unresolved=0     (对 libGDX 1.1.0)
native_gap 121 demanded / 121 satisfied / 0 missing       (对 libGDX 1.1.0)
SmokeTest                 SMOKE TEST PASSED               (23 个 iOS 商品 id 全部路由)
javac core (5 files)      exit=0  → 8 个 class
javac ios  (1 file)       exit=0  → 1 个 class
OggToWav   102 converted / 0 failed / 26,700,700 bytes
资源对账    APK 保留目录 1586 个文件 → iOS bundle 0 缺失
音频对账    字节码引用 99 个 ogg → 99 个 wav 全部在位
```

`SmokeTest` 覆盖：平台标识、16 张奖励表、`loadGame/saveGame` 确实覆写、4 个 `pay` 重载都同步回调成功、23 个 iOS 商品 id（19–34、42–48）全部可路由。

另外用字节码常量池扫过 2838 个资源路径引用，其中 605 个在 iOS bundle 里找不到 —— **这些在原始 APK 的 assets 里同样不存在**，是运行时拼接的路径和 atlas region 名，不是打包遗漏。

---

## 已知未验证项

这些只能在真机上确认，静态分析覆盖不到：

1. **Spine 二进制骨骼**（176 个 `.skel`，magic `00 04 05`）—— 用的是游戏自带的 Spine 运行时，版本需与资源匹配，静态看不出是否一致。
2. **GLES 渲染路径** —— `IOSGLES20` 的 native 符号 121/121 对得上，但实际 GL 调用序列没跑过。
3. **存档读写** —— `SaveData` 用 `Gdx.files.local()`，`IOSFiles.localPath = $HOME/Library/local`，路径存在但首次运行建目录的行为没实测。
4. **`SynTime` 服务器时间** —— 已把 `DEBUG_MODE` 从 `true` 改成 `false`（原来指向 `192.168.2.87` 内网测试地址），正式地址 `115.28.162.6:8080` 是否还活着不确定；连不上时 `getTime()` 返回 `-1`，游戏有降级逻辑。

---

## 真机运行

真机这条路有两个岔口，取决于你手上的设备系统版本。

### 路线 A：设备在 iOS ≤ 10.3.3（当前配置直接可用）

适用设备：iPhone 4S / 5 / 5c、iPad 2 / 3 / 4、iPad mini 1、iPod touch 5 —— 这些机型的官方系统上限就是 10.3.3，仍能运行 armv7。

**⚠️ 还有一个硬门槛：Xcode 必须是 13 或更早。** Apple 在 [Xcode 14 里彻底移除了 32 位构建能力](https://developer.apple.com/news/upcoming-requirements/?id=06062022a)，Xcode 14+ 编 armv7 会直接报错。所以这条路要的是「老 Xcode + 老设备」的组合：

| Xcode | 能编 armv7 | 能在 macOS 上跑 |
|---|---|---|
| 13.x | ✅ | macOS 11.3 – 12.x |
| 14.x+ | ❌ | macOS 12+ |

要跑 Xcode 13 就得有 macOS 12 Monterey 或更早的机器/虚拟机。

**准备（Windows 上能做的部分已经做完了）**

依赖和资源都已就位，`build.gradle` / `robovm.xml` / `Info.plist.xml` / 图标启动图 / 1698 个资源全部备好。

**第一步：macOS 上装工具链**

```bash
# JDK 8（RoboVM 2.3.19 只认 JDK 8，JDK 11+ 会报 sun.misc 相关错误）
brew install --cask temurin@8
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)

# Gradle 7.x
brew install gradle@7

# Xcode 13.x（从 developer.apple.com/download/all 下，需要 Apple ID）
```

**第二步：拿签名身份**

RoboVM 需要两样东西：`.p12` 证书 + `.mobileprovision` 描述文件。

- 免费 Apple ID 也能签（7 天有效期，最多 3 个 App），在 Xcode → Preferences → Accounts 登录后点 "Manage Certificates" 生成 "Apple Development" 证书；
- 想签长期就用付费开发者账号（$99/年），描述文件有效期 1 年。

导出 `.p12` 后放到 `ios/` 下，描述文件同理。

**第三步：改 `build.gradle` 的签名段**

```groovy
robovm {
    archs 'thumbv7'
    iosSkipSigning = false                                  // 从 true 改成 false
    iosSignIdentity = 'iPhone Developer: Your Name (XXXXXXXXXX)'   // security find-identity -v -p codesigning 能看到
    iosProvisioningProfile = 'path/to/YourProfile.mobileprovision'
}
```

**第四步：构建 + 装机**

```bash
cd ios-port
gradle ios:createIPA
# 产物在 ios/build/robovm/ 下

# 或用 gradle 直接推到设备（任务名已核对过 RoboVM 2.3.19 的插件）
gradle ios:launchIPhoneSimulator    # 模拟器先验证
gradle ios:launchIOSDevice          # 真机运行 + 装上去
gradle ios:robovmInstall            # 只编译安装，不启动
```

**第五步：信任证书**

首次安装后 App 起不来是正常的，去 **设置 → 通用 → VPN与设备管理 → 开发者App** 里信任你的证书。

---

### 路线 B：设备在 iOS 11+（必须自己重编 arm64 原生库）

这条路工作量大但可行 —— 我核对了 libGDX 1.1.0 的 C 源码，**它是架构中立的**：

| 文件 | 大小 | 架构相关代码 |
|---|---|---|
| `gdx2d/gdx2d.c` | 32,346 B | 0 处 |
| `gdx2d/jpgd.cpp` | 87,961 B | 0 处 |
| `iosgl/iosgl20.cpp` | 55,149 B | 0 处 |
| `iosgl/...IOSGLES10.cpp` | 35,068 B | 0 处 |
| `com.badlogic.gdx.math.Matrix4.cpp` | 13,275 B | 0 处 |
| `com.badlogic.gdx.utils.BufferUtils.cpp` | 25,802 B | 0 处（4 处指针转换，无 `(int)(jlong)` 截断） |

`build-ios32.xml` 里的 arm 编译命令就是 `clang++ -isysroot <sdk> -arch armv7`，把 `-arch armv7` 换成 `-arch arm64` 就行。

**关键洞察：Java 侧完全不用动。** `game-ios.jar` 里的 libGDX 1.1.0 Java 类保持原样，只要原生符号名对得上（`native_gap.py` 已验证 121/121），JNI 就能绑定 —— 因为 JNI 符号名只跟 Java 方法签名有关，跟目标架构无关。

**RoboVM 侧也没问题**：`robovm-compiler-2.3.19.jar` 的 `org.robovm.compiler.config.Arch` 枚举只有三个值 —— `thumbv7`、`arm64`、`x86_64`（`Arch.parse(String)` 可解析），所以 `archs 'arm64'` 是被支持的。反倒是 `thumbv7` 需要老 Xcode，`arm64` 才是现代工具链的默认路线。

**具体步骤**

1. **在 macOS 上拿源码**
   ```bash
   git clone --depth 1 --branch 1.1.0 https://github.com/libgdx/libgdx.git
   cd libgdx/gdx/jni
   ```

2. **改 `build-ios32.xml`**：复制一份成 `build-ios64.xml`，把三处 `-arch armv7` 改成 `-arch arm64`，`-arch i386` 改成 `-arch x86_64`（模拟器用），`lipo -create` 那一行也相应改。

3. **编译**
   ```bash
   ant -f build-ios64.xml postcompile
   # 产物 libs/libgdx.a（含 arm64）
   ```

4. **FreeType 库同样重编**（`extensions/gdx-freetype/jni/`，同样是 `-arch armv7` → `-arch arm64`）。注意这个库在 `useHiero=true` 时根本不会加载，可以最后再做。

5. **ObjectAL** 是第三方库（[alinebee/ObjectAL-for-iPhone](https://github.com/alunbestee/ObjectAL-for-iPhone)，2014 年后未更新），需要同样按 arm64 重编，或者换成 AVAudioPlayer 直调。

6. **替换并改配置**
   ```bash
   cp libgdx.a ios-port/ios/libs/ios/
   # robovm.xml: <arch>thumbv7</arch> → <arch>arm64</arch>
   # build.gradle: archs 'thumbv7' → archs 'arm64'
   ```

7. **验证符号仍然齐全**（这一步别跳过，换库后必须重跑）
   ```bash
   python tools/native_gap.py build/native-decls.txt ios/libs/ios/libgdx.a
   # 期望：demanded 121, satisfied 121, missing 0
   ```

**风险点**：`robovm-cocoatouch-2.3.19.jar` 的 Objective-C 桥接层是 RoboVM 自己的，arm64 支持没问题；但如果 spine 运行时里有 native 部分（当前看是纯 Java，无 `.so`），也要一并处理。

---

### 路线 C：不上真机，先验证逻辑

没有 iOS 设备也能跑一部分：`SmokeTest` 已经在桌面 JVM 上验证了平台层的全部纯逻辑（23 个商品 id、16 张奖励表、存档覆写、支付回调）。真正需要设备的是 GL 渲染、音频播放、Spine 骨骼加载。

---

## 上架 App Store（路线 B 之上的额外工作）

即使按路线 B 编出 arm64，上架还要：

1. **换掉 libGDX 1.9.14 的 Java 侧**？不需要 —— 继续用 1.1.0 的 Java 类 + 自编 arm64 原生库即可，这样避开了那 30 处 API 断裂。
2. 补 1024×1024 商店图标（已生成 `Icon-1024.png`）和完整截图。
3. 隐私清单（`PrivacyInfo.xcprivacy`）—— iOS 17 起强制。
4. 这个包去掉了全部渠道 SDK 和统计，所以不需要 ATT 授权弹窗。
