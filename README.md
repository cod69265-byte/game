# 燃烧的蔬菜 3 HD — iOS 26.3 移植

把 2015 年的 Android 版《燃烧的蔬菜3 HD》（4399 渠道破解版）移植到 **iOS 26.3**。

原版是 libGDX 1.1.0 + armv7 + OpenGL ES，这三样在 iOS 26 上全部不可用：

| 项目 | 原版 | 现在 | 为什么必须换 |
|---|---|---|---|
| CPU 架构 | armv7（32 位） | **arm64** | iOS 11 起系统完全不执行 32 位程序，Xcode 14 起也编不出来 |
| 图形 API | OpenGL ES 2/3 | **Metal**（经 MetalANGLE） | iOS 26 不再提供 OpenGL ES 驱动，GL 调用无处落地 |
| 引擎 | libGDX 1.1.0 | **libGDX 1.14.2** | 只有 1.12.0+ 才有 MetalANGLE 后端 |

---

## 怎么拿到能装到手机上的 IPA

**Windows 上做不到。** RoboVM 的 AOT 编译器只有 macOS 版 —— 实测到第 7 步报
`UnsatisfiedLinkError: Native library for windows-x86_64 not found`，前 6 步
（配置解析、SDK 识别、framework 路径、mainClass、arch=arm64）全部通过。

### 路线 A：GitHub Actions（不需要 Mac，免费）

1. 本仓库 → **Actions** → 左侧 `build-ios26-ipa` → **Run workflow**
2. flavour 选 `ios26scene`（UIScene 生命周期，iOS 27 也兼容）
3. 等 10–15 分钟，在 Artifacts 里下载 `Veggies3HD-ios26scene-unsigned`
4. 解压得到 `.ipa`

### 路线 B：借一台 Mac

```bash
./setupLinks.sh                    # 重建 5 个被 git 丢弃的目录链接
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
./gradlew :ios26scene:createIPA    # 需要 Xcode 15+
```

### 装到手机（Windows 就能做）

1. 从苹果官网装 **iTunes + iCloud**（不要用 Microsoft Store 版，Sideloadly 认不出设备）
2. 装 [Sideloadly](https://sideloadly.io/)，数据线连手机，IPA 拖进去，填 Apple ID
3. 手机 设置 → 通用 → VPN与设备管理 → 信任你的证书

免费 Apple ID 的限制：签名 **7 天过期**、同时最多 **3 个**自签 App、一周最多签 **10 次**。
要长期用就买开发者账号（$99/年）或用 AltStore 自动续签。

完整步骤见 [INSTALL.md](INSTALL.md)。

---

## 技术做法

**没有重新编译游戏的 Java 代码。** 反编译出来 2150 个文件，其中约 20 个因为
反编译器处理不了 `try/finally` 的变量作用域而无法修复。所以策略是：

> 保持游戏字节码原样（`libs/game-ios.jar`），只给 libGDX 侧打兼容补丁。

`tools/src/InjectShim.java` 用 ASM 往 libGDX 1.14.2 里注入 26 个 1.1.0 才有的成员，
把 API 缺口从 **34 处降到 0**：

- 整个 `BitmapFont$TextBounds` 类（1.14.2 换成了 `GlyphLayout`）
- `BitmapFont.setScale(float)`、`getRegions()[TextureRegion`、`getBounds`、`draw`
- `ParticleEmitter.getScale()/getSprite()`、`ParticleEffectPool.obtain()`
- `Input.setCatchBackKey/setCatchMenuKey`、三参 `getTextInput`
- `FreeType` 的 5 个静态方法、`FreeType$Bitmap.getPixmap`、`FrameBuffer.getColorBufferTexture`
- `GLTexture.createGLHandle()`

另外补了三个会让真机**第一帧之前就崩**的坑：

1. **`com.badlogic.gdx.utils.Os` 与 `SharedLibraryLoader`** —— 1.14.2 把它们拆到独立
   artifact（Maven 上 404）。但 `UIUtils` 的静态块会读 `SharedLibraryLoader.os`，
   `FreeType` 的静态块会 `new SharedLibraryLoader().load(...)`。不补就 `NoClassDefFoundError`。
2. **`Preferences.putXxx` 返回类型**从 `void` 变成 `Preferences` —— 同一份源码不能同时满足
   两个 flavour，所以 `:core26` 用 `sourceSets` 指向同一目录重编一次。
3. **iOS 27 要求 UIScene 生命周期**（TN3187）—— 单独做了 `ios26scene` 变体，
   plist 里的 `UISceneDelegateClassName` 必须写 RoboVM 的 ObjC 命名
   `j_` + 类名.replace(`.`,`_`)（`$` 原样保留）。写错的话 UIKit 找不到类、不调
   `willConnect`、窗口永不挂上 scene → **黑屏且无任何日志**。

内购沿用原破解版：`IOSPlatForm.pay()` 直接 `payNotify(true, orderId)`，
23 个 iOS 商品 ID（19–34、42–48）全部可路由，`UI_chongzhi.isIOS` 用字节码补丁
（`iconst_0` → `iconst_1`）打开，显示 iOS 价格表。

---

## 验证

```bash
./gradlew verifyAll     # 或 Windows 上：powershell -File tools/verify-all.ps1
```

13 项全绿：

```
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

**没有在真机上跑过。** 没有 Mac 也没有 iPhone。首次启动如果闪退或黑屏，
[README-iOS26.md](README-iOS26.md) 第 7 节列了 12 条已知限制和排查方向。

---

## 目录

```
core/src/main/java/          5 个手写文件（IOSPlatForm 等）
core26/                      同一份源码按 libGDX 1.14.2 重编
ios/                         资源 1698 个 + 30 图标 + legacy armv7 静态库
ios26/                       iOS 26 启动器（legacy UIApplicationDelegate）
ios26scene/                  同上 + UIScene 生命周期（iOS 27 就绪）
libs/                        game-ios.jar / game-core2.jar / gdx-1.14.2-ios.jar / jorbis.jar
tools/src/                   13 个验证与注入工具（Java + ASM）
ios/libs/ios26/              7 个 arm64 framework（Mach-O 0xFEEDFACF, cputype 16777228）
```

`ios26/resources`、`ios26/libs`、`ios26scene/*` 在原始工程里是 NTFS junction，
git 存不了链接，克隆后跑一次 `./setupLinks.sh` 重建。

---

## 文档

- [INSTALL.md](INSTALL.md) — 从零到手机安装，含 FAQ
- [README-iOS26.md](README-iOS26.md) — 完整技术说明、验证输出、12 条已知限制
- [README-iOS.md](README-iOS.md) — legacy 路线（iOS ≤ 10，armv7）
- [ios26-libs-README.txt](ios26-libs-README.txt) — 7 个 framework 的来源与布局
- [.github/SIGNING.md](.github/SIGNING.md) — 签名与真机部署

---

## 声明

原版游戏版权归 SOCO / 4399 所有。本仓库只包含移植所需的构建配置、平台层代码
和逆向工程产物，不主张任何权利。请自行确认在你所在地区的合法性。

---

## CI run #7 复盘：80 分钟沉默期定位完成（进程级证据）

`tools/ci_sample.sh` 采样器在 run #7 抓到了决定性数据：

| 证据 | 内容 |
|---|---|
| proc.log | java 进程（Gradle daemon）**100% CPU 单核**持续 84 分钟，无任何 clang/lipo 子进程 |
| 6 份栈采样（横跨 12–84 min） | ~70% 样本落在 JIT 编译的 Java 代码（0x104b–0x10799，Binary Images 之外），GC 线程/主线程全部 park |
| Binary Images | `librobovm-llvm*.dylib` 已加载（0x129944000）但栈上无其帧 → LLVM 本体没在跑 |
| `~/.robovm/cache/ios/arm64` | **空目录** —— soot 没完成，从未走到写缓存 |

时间线：10 分钟编完 1943 类的 toC（"Compiling X (ios arm64 release)"，10:11–10:12 + 10:19–10:20 两批 + cancel 前 100 类收尾）；中间 80 分钟零日志 = **soot 全程序分析（单线程，RoboVM 2.3.19 硬限制）**。

结论：toC/LLVM 不是瓶颈；瓶颈是 soot 对 ~4,700 类闭包的单线程 Java 分析。加速选项：
1. `actions/cache`（本轮已加）对 soot 无效——缓存写发生在 soot 之后，首跑取消则永远存不进。
2. 真正杠杆：升级 RoboVM 3.0.0（soot 新版 + 改进的分析管线）或削减类闭包。
3. force-link 的 7 个包（com.soco/com.badlogic.gdx/com.esotericsoftware/com.main/com.net/com.protocol/javazoom）经字节码引用检查全部为真实引用，无法删除。