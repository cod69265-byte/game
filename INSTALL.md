# 在 iPhone 上安装运行 —— 完整路径

> **先说结论**：现在这个目录里**还没有 `.ipa` 文件**。
> 里面是可构建的完整工程 + 全部离线验证结果，但 RoboVM 的 AOT 编译器只在 macOS 上工作，
> 而这次移植是在 Windows 上做的。所以「下载安装」的第一步永远是**先产出一个 IPA**，
> 而产出 IPA 需要一台 Mac（或免费的 GitHub Actions macOS runner）。
>
> 下面从「拿到 IPA」讲到「装进手机」，四条路线按你的条件选一条。

---

## 路线总览

| 路线 | 需要什么 | 有效期 | 适合谁 |
|---|---|---|---|
| **A. GitHub Actions 构建 + 侧载** | 一个 GitHub 账号 + 一台 iPhone | 免费账号 7 天 | **推荐**，不需要 Mac |
| **B. 借一台 Mac 构建 + 侧载** | 任意 Mac + Xcode + iPhone | 同上 | 有 Mac 朋友 |
| **C. 付费开发者账号签名** | $99/年 Apple Developer | 1 年 | 长期玩 |
| **D. 越狱设备直接装** | 已越狱的 iPhone | 永久 | 老设备/折腾党 |

**共同前提**：`ios26`（legacy 生命周期）在 iOS 26.3 能跑；但如果你打算长期用，直接出 `ios26scene`（UIScene 生命周期），iOS 27 也不会失效。

---

## 第一步（所有路线共用）：产出 IPA

### A. 用 GitHub Actions（没有 Mac 时唯一可行）

1. 新建一个 GitHub 仓库（可以是 private）。
2. 把 `ios-port/` 整个目录推上去：

```bash
cd ios-port
git init
git add .
git commit -m "Veggies3HD iOS 26 port"
git remote add origin https://github.com/<你的账号>/veggies3hd-ios.git
git push -u origin main
```

`.gitignore` 已经把 `offline-repo/`（173 MB）和 `work/`（反编译草稿）排除掉了 ——
CI runner 有网络，会自己从 mavenCentral 拉同样的 jar。

3. 仓库页面 → **Actions** → 左侧 `build-ios26-ipa` → **Run workflow**
   - `flavour` 选 `ios26scene`（推荐）或 `ios26`
4. 等约 15–25 分钟。跑完后在该次 run 页面底部 **Artifacts** 下载
   `Veggies3HD-ios26scene-unsigned.zip`，解压得到 `.ipa`。

> runner 是 `macos-15`（自带 Xcode 16），工作流里已含 `setupLinks.sh`（重建被 git 丢掉的目录链接）和 JDK 8 设置。
> 免费额度：public 仓库无限，private 仓库每月 2000 分钟。

### B. 有 Mac 时

```bash
cd ios-port
chmod +x setupLinks.sh gradlew
./setupLinks.sh                    # 重建 ios26/libs 等链接
java -version                      # 必须是 1.8.x
xcodebuild -version                # 必须 15+
./gradlew :ios26scene:createIPA
```

产物在 `ios26scene/build/robovm/*.ipa`。

> **JDK 必须是 8**。RoboVM 2.3.19 用了 JDK 9 已移除的 `com.sun.javadoc` 接口，
> JDK 11/17/21 会在 AOT 阶段直接崩。

---

## 第二步：装进 iPhone

### 侧载（路线 A/B/C 通用）

免费 Apple ID 就能签，但**只有 7 天有效期**，到期要重新签一次。

**用 Sideloadly（Windows/Mac 都能跑，最省事）**

1. 电脑装 [Sideloadly](https://sideloadly.io/)（Windows 需先装 iTunes 和 iCloud，从苹果官网下，不要 Microsoft Store 版）。
2. iPhone 用数据线连电脑，手机上点「信任此电脑」。
3. Sideloadly 里：
   - **IPA** 选第一步拿到的 `.ipa`
   - **Apple ID** 填你的 Apple ID（会跳二次验证，正常填）
   - 点 **Start**
4. 手机上：**设置 → 通用 → VPN与设备管理 → 开发者应用** → 点你的 Apple ID → **信任**。
5. 回桌面点图标就能玩。

**用 AltStore（需要常驻电脑/树莓派做 AltServer 自动续签）**

适合想自动续期的人，配置比 Sideloadly 麻烦。iOS 26 上仍可用。

**免费账号的三个限制**（这是苹果的规则，绕不开）：
- 7 天过期，过期后 App 打不开，要重新签
- 同时最多 3 个自签 App
- 同一 Apple ID 一周内最多签 10 次

### 路线 C：付费开发者账号

$99/年，签出来的 IPA **1 年有效**，还能用 TestFlight 分发给别人（最多 100 台设备）。
如果打算长期玩或者给朋友装，这是最省心的。

### 路线 D：越狱设备

直接 `scp` 到设备或用 Filza 装，不需要签名。但 iOS 26 目前没有公开越狱，
这条路只对老设备（iOS ≤ 16）现实。

---

## 常见问题

**Q: 装完打开闪退？**
A: 先确认装的是 `ios26`/`ios26scene` 的包，不是 `ios`（那个是 armv7，iOS 11 之后系统根本不执行 32 位程序）。再看是不是签名过期了（免费账号 7 天）。

**Q: 黑屏但没闪退？**
A: 如果你用的是 `ios26scene`，最可能是 plist 里的 `UISceneDelegateClassName` 和 RoboVM 生成的 ObjC 类名对不上。
   这个名字的规则是 `"j_" + 类名.replace('.', '_')`，**内层类的 `$` 原样保留**。
   工程里 `tools/ObjCNameCheck.java` 就是专门核对这一条的，构建前可以本地跑一遍。

**Q: 内购能用吗？**
A: 能。`IOSPlatForm` 的四个 `pay(...)` 重载直接回调 `payNotify(true, orderId)`，
   和原破解版行为一致 —— 23 个 iOS 商品 id（19–34、42–48）全部无条件成功。
   魂石/金币/钻石直接到账，不经过 App Store。

**Q: 需要联网吗？**
A: 不需要。游戏本身是单机。原游戏的 `socogame.com` 服务器多数已下线，
   `SynTime.refresh()` 会超时但不影响游玩（`DEBUG_MODE` 已设为 `false`，
   不会去连内网测试地址 `192.168.2.87`）。

**Q: 存档在哪？能导出来吗？**
A: 在 App 沙盒的 `Library/local/FireVeg201406`，格式是 4 字节 CRC32 头 + Java 序列化体。
   和安卓版同名同格式，理论上可以互导（两边都用 `SaveData` 的通用路径）。

**Q: 我能直接改代码重新打包吗？**
A: 可以。游戏字节码在 `libs/game-ios.jar`，libGDX 兼容层由 `tools/src/InjectShim.java`
   用 ASM 注入生成（`libs/gdx-1.14.2-ios.jar`）。改完重跑 InjectShim 再构建即可。

---

## 签名配置（路线 C 用）

工程里 `ios26/signing.gradle.template` 是模板，把三行填好并入 `build.gradle` 的
`project(':ios26scene') { robovm { ... } }`：

```groovy
robovm {
    archs 'arm64'
    iosSkipSigning = false
    iosSignIdentity = "Apple Development: you@example.com (XXXXXXXXXX)"
    iosProvisioningProfile = "/path/to/your.mobileprovision"
    configFile file('robovm-ios26scene.xml')
}
```

`CFBundleIdentifier` 在 plist 里是 `${app.id}`，构建时覆盖成与 provisioning profile 匹配的值：

```bash
./gradlew :ios26scene:createIPA -Probovm.app.id=com.yourorg.veggies3
```

---

## 构建链在 Windows 上实测到哪一步（证据）

不是"理论上能构建"，而是逐步跑过、逐步卡住的。每一步的失败原因都已定位到源码行号：

| # | 命令 | 结果 | 原因 |
|---|---|---|---|
| 1 | `gradlew.bat projects` | ✅ BUILD SUCCESSFUL | 识别 :core :core26 :ios :ios26 :ios26scene |
| 2 | `gradlew.bat :core26:compileJava :ios26:compileJava :ios26scene:compileJava` | ✅ BUILD SUCCESSFUL in 21s | 13 个 class 全部编译通过 |
| 3 | `gradlew.bat :ios26scene:createIPA` | ❌ `Cannot run program "xcode-select"` | `ToolchainUtil.findXcodePath()` 要调 macOS 的 `xcode-select --print-path` |
| 4 | 同上 + PATH 里放一个假的 `xcode-select.exe` | ❌ `No device SDKs installed` | `SDK.listBundledFileFormatSdks()` 读 `<Xcode>/Platforms/iPhoneOS.platform/Developer/SDKs/*/SDKSettings.plist` |
| 5 | 同上 + 造出完整 SDK 目录树 | ❌ `No device SDKs installed`（换了位置） | `SDK.create()` 还要求同目录有 `System/Library/CoreServices/SystemVersion.plist`、`../../../version.plist`、`../../../Info.plist`（且后者要有 `AdditionalInfo.DTPlatformVersion`） |
| 6 | 同上 + 补齐四个 plist | ⚠️ `Config.build()` 通过，`IOSTarget.init()` 通过 | 假 SDK 被正确识别为 `iOS 26.0 / iphoneos26.0` |
| 7 | 同上继续 | ❌ `UnsatisfiedLinkError: Native library for windows-x86_64 not found` | **RoboVM 的 AOT 编译器本体没有 Windows 版**，只有 macOS/Linux 版 |

第 6 步是很有价值的证据：**我写的 `robovm-ios26scene.xml`、`Info-scene.plist.xml`、`robovm.properties`、7 个 arm64 framework 路径、`mainClass`、`arch=arm64`，全部被 RoboVM 的配置解析器接受了**，没有任何一处报"找不到 framework"或"配置非法"。剩下的纯粹是编译器二进制不可用。

所以：配置层面已无未知风险，剩下的失败都是"这台机器不是 macOS"。

## 尚未验证的部分（如实说明）

这套工程在 Windows 上做了 12 项离线验证，全部通过：

```
LinkCheck    游戏→libGDX 1.14.2        470 类 0 缺口
AccessCheck  64,828 次成员访问          0 可见性违规
VerifyJar    注入后的字节码             1179 类 0 问题
RuntimeContract / LaunchCheck / ObjCNameCheck  全过
音频 102 个 ogg→wav、资源 1698 个       0 缺失
```

但**没有在真机上跑过**。真机上可能暴露的问题：MetalANGLE 对某些 GLES2 扩展的实现差异、
Spine 二进制 `.skel` 的版本兼容。这些只能在设备上撞。如果黑屏/闪退，
把 Xcode 的 Devices → Open Console 里的日志贴出来，比盲猜快得多。
