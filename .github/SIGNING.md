# 签名与真机部署

CI 产出的是 **未签名 IPA**。未签名 IPA 只能装在越狱设备或模拟器上。
普通 iPhone 需要签名，两条路：

---

## 一、免费 Apple ID 自签（7 天有效期）

### Windows

1. 从苹果官网下载安装 **iTunes** 和 **iCloud for Windows**
   - 必须是官网 `.exe`，**不要用 Microsoft Store 版** —— Store 版是 UWP 沙盒应用，
     Sideloadly / AltStore 通过 `usbmuxd` 找设备，Store 版没有这个通道，会一直显示
     "no device detected"。
2. 安装 [Sideloadly](https://sideloadly.io/)
3. 数据线连 iPhone，解锁并点"信任此电脑"
4. Sideloadly 里：
   - IPA 文件拖进窗口
   - Apple ID 填你的邮箱
   - 点 Start，输入 Apple ID 密码（密码只在本机用于换取签名证书，不会上传到第三方）
5. 手机：设置 → 通用 → VPN与设备管理 → 开发者 App → 点你的 Apple ID → 信任

### macOS

```bash
brew install --cask sideloadly
```

或用 AltStore（能自动 7 天续签，需要 AltServer 常驻一台电脑）。

### 免费账号的硬限制

| 限制 | 数值 | 后果 |
|---|---|---|
| 证书有效期 | **7 天** | 到期后 App 打不开，要重新签 |
| 同时安装的自签 App | **3 个** | 装第 4 个必须先删一个 |
| 每周签名次数 | **10 次** | 超了要等 |

### 不想每周重签

- 买 **Apple Developer Program**（$99/年）：证书 1 年有效，可装 100 台设备
- 或 AltStore + AltServer 自动续签（电脑需定期开机）

---

## 二、正式签名（开发者账号）

### 2.1 准备证书和描述文件

1. 登录 [developer.apple.com](https://developer.apple.com/account) → Certificates, IDs & Profiles
2. 建一个 **App ID**：Bundle ID 填 `com.soco.veggies3_4399`
   - 如果用别的 Bundle ID，同步改 `ios26/robovm.properties` 的 `app.id`
3. 建 **iOS Development** 证书（或 Distribution 用于 TestFlight/上架），下载 `.cer` 双击导入钥匙串
4. 注册设备 UDID，建 **Provisioning Profile**，下载 `.mobileprovision`

### 2.2 让 Gradle 用上

复制模板：

```bash
cp ios26/signing.gradle.template ios26/signing.gradle
```

编辑 `ios26/signing.gradle`：

```groovy
robovm {
    iosSignIdentity = 'iPhone Developer: Your Name (XXXXXXXXXX)'
    iosProvisioningProfile = '/absolute/path/to/xxx.mobileprovision'
    iosSkipSigning = false
}
```

`iosSignIdentity` 的准确字符串：

```bash
security find-identity -v -p codesigning
```

然后在 `build.gradle` 的 `project(':ios26')` 块里加一行（`signing.gradle` 已在
`.gitignore` 之外，注意不要把它连同证书路径提交上去）：

```groovy
apply from: file('signing.gradle')
```

构建：

```bash
./gradlew :ios26scene:createIPA
```

### 2.3 命令行直接签名（CI 用）

```bash
codesign -f -s "iPhone Developer: Your Name (XXXXXXXXXX)" \
  --entitlements ios26/entitlements.plist \
  Payload/Veggies3HDScene.app

zip -qr Veggies3HD-signed.ipa Payload
```

### 2.4 装到设备

- **Xcode**：Window → Devices and Simulators → 拖 `.app` 进去
- **Apple Configurator 2**：拖 `.ipa`
- **TestFlight**：上传到 App Store Connect（需要 Distribution 证书 + App 记录）

---

## 三、常见失败

| 报错 | 原因 | 处理 |
|---|---|---|
| `no device detected` | 装了 Store 版 iTunes/iCloud | 卸载，装官网版 |
| `Provisioning profile doesn't include signing certificate` | 证书和描述文件不匹配 | 在开发者后台重新生成描述文件 |
| `Unable to install ... integrity could not be verified` | 签名后 App 内容被改过 | 重新打包，不要手改 `.app` 里的文件 |
| 装上了但点开闪退 | 签名过期 / 架构不对 / 首次信任没做 | 先看 `README-iOS26.md` 第 7 节；确认装的是 arm64 包（`ios26` 或 `ios26scene`，不是 `ios`） |
| 打开黑屏无崩溃 | `UISceneDelegateClassName` 与 RoboVM 生成的 ObjC 类名对不上 | 跑 `java -cp ... ObjCNameCheck` 核对，见 `README-iOS26.md` §4.5 |

---

## 四、entitlements

RoboVM 2.3.19 生成的 entitlements 默认包含 `get-task-allow`（调试用）。
如果要上架或做 Ad Hoc 分发，需要去掉。参考 `ios26/entitlements.plist`（如不存在则
由 RoboVM 自动生成，位置在 `build/robovm/` 下）。
