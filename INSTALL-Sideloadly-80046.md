# 装不上手机的真正原因：Apple 的 anisette 策略变了（不是 IPA 的问题）

## 结论先行

`Sideloadly` 报的

```
Login failed (-80046): This action cannot be completed at this time.
Guru Meditation 0db732@418:3aea77
```

**和这个 IPA、和你的 Apple ID、和网络都无关。** 是 Apple 在 2026-10-12 之后改了策略：
它开始拒绝**由 Apple 安卓库（ADI）派生出来的 anisette 数据**，无论客户端声称自己是什么设备。
密码校验和两步验证都能过，但最后换 token 那一步被拒 —— 正好就是 `-80046`。

Windows 版 Sideloadly 的 `Local Anisette` 走的正是这条路（`an\CoreADI.dll` + `an\CoreFP.dll`），
所以它必然失败。而 Sideloadly 的 `Remote Anisette`（走真 Mac 服务）是 **Patreon 付费功能**，
`patreon-auth` 里 `"paid": false`，所以也用不了。

## 本机实测证据（2026-10-24 夜）

### 1. IPA 完全合格

```
App: 燃烧的蔬菜3HD  (com.soco.veggies3_4399)
  bundle: Veggies3HD.app   executable: Veggies3HD
  version: 1.5.2 (build 1.5.2)   min iOS: 12.0   built-with SDK: 18.5
  frameworks: 7   app extensions: 0   watch app: False
  embedded provision: False   FairPlay hint (SC_Info): False
```

主二进制 31,696,544 B，`0xfeedfacf` / cputype `16777228` = **arm64**，
`_CodeSignature/CodeResources` 在位，22 个图标 + 8 张启动图齐全，
7 个 framework（MetalANGLEKit / libGLESv2 / libEGL / libfeature_support / gdx / ObjectAL / gdx-freetype）全部打包。

### 2. 证书链问题已修（但这不是 -80046 的原因）

`gsa.apple.com` 挂在 `Apple Root CA` 下，而本机 Windows 根存储和 Sideloadly 自带 certifi 都没有这张根证书。
修复脚本：`tools/fix-windows-tls.ps1`（幂等，会校验 SHA-1 指纹 `611E5B662C593A08FF58D14AE22452D198DF6C60`）。
修完后 `.NET chain build = True`，`POST /grandslam/GsService2` 从 TLS 失败变成正常的应用层回应。

**但修完仍然是 `-80046`** —— 因为它不是证书问题。

### 3. 三个免费 Mac anisette 服务全部可用

```
mac-ani.jkcoxson.com    200  0.95s
new.ani.sidestore.io    200  1.29s
ani3.stikstore.app      200  1.07s
```

它们都能返回完整的 anisette 头（`X-Apple-I-MD` / `MD-M` / `MD-LU` / `MD-RINFO`）。

### 4. 决定性验证：用不存在的账号探测

用 `dsh_probe_nosuch_2026@example.com` / 假密码走 iPASide（已改用 Mac anisette）：

```
Login failed: Apple error -20970: 你输入的账户信息不正确。
```

`-20970` 是 Apple 对**不存在账号**给出的正常回应 ——
说明请求真的到达了认证服务，anisette 被接受了。
对比 Sideloadly 在同一台机器、同一个网络下拿到的 `-80046`（anisette 被拒），差别就在 anisette 来源。

## 解决办法：改用 iPASide（免费、开源、已修好）

[iPASide](https://github.com/pwnapplehat/iPASide) 是 Windows 上的开源侧载工具，
1.2.9（2026-10-23）专门修了 `-80046`：改用 Mac anisette 服务，并自动在三个服务间轮换。
1.2.10（2026-10-24）是当前版本。

### 已经在这台机器上装好了

```
C:\Users\oo\AppData\Local\Programs\iPASide\iPASide.exe        (GUI)
C:\Users\oo\AppData\Local\Programs\iPASide\engine\python\python.exe   (引擎 + CLI)
```

`doctor` 全绿：

```
[ OK ]  Python runtime            3.12.8
[ OK ]  pymobiledevice3           v11.43.1
[ OK ]  Apple Mobile Device Service   running; USB device I/O is available
[ OK ]  Anisette provider         remote Mac service; identity provisioned
[ OK ]  Bonjour (Wi-Fi discovery) running
[WARN]  Flutter SDK               not found - needed to build the GUI, not the engine
[ OK ]  Connected iOS devices     1 device: 00008150-000168111E82401C (USB)
```

（`Flutter SDK` 那条只影响**从源码编译 GUI**，不影响使用已安装的 GUI。）

### 操作步骤

1. 双击 `C:\Users\oo\AppData\Local\Programs\iPASide\iPASide.exe`
2. 点 **Sign in**，输入 Apple ID 邮箱 + 密码
3. Apple 会往你的受信任设备推 6 位验证码，填进去（也支持短信）
4. 把 `C:\Users\oo\Desktop\自己\Veggies3HD-ios26.3-arm64.ipa` 拖进窗口
5. 点 **Sideload**

它会自动完成：provision（申请证书 + 注册设备 + 建 App ID + 下 profile）→ sign（zsign）→ install。

### 命令行等价写法

```powershell
$py = "C:\Users\oo\AppData\Local\Programs\iPASide\engine\python\python.exe"
$env:PYTHONPATH = "C:\Users\oo\AppData\Local\Programs\iPASide\engine\python\Lib\site-packages"

# 登录（会提示输入密码和 2FA）
& $py -m ipaside_engine login --email 你的AppleID@example.com

# 一步完成 provision + sign + install
& $py -m ipaside_engine sideload "C:\Users\oo\Desktop\自己\Veggies3HD-ios26.3-arm64.ipa"
```

## 免费 Apple ID 的三个硬限制

| 限制 | 数值 | 说明 |
|---|---|---|
| 签名有效期 | **7 天** | 到期后 App 打不开，需要重新 sideload 或 `refresh` |
| 同时安装的侧载 App | **3 个** | 按**设备**计，不是按账号计；换账号不增加名额 |
| 每周 App ID 注册 | **10 个** | 这是另一个上限，和上面 3 个不是一回事 |

`iPASide` 有 `refresh` 命令可以在到期前自动重签重装：

```powershell
& $py -m ipaside_engine installs     # 看还剩几天
& $py -m ipaside_engine refresh      # 快到期时重签
```

## 不要再做的三件事

1. **不要反复重试 Sideloadly。** `-80046` 是服务端策略，重试只会喂限流（会从 `-80046` 变成 `429`）。
2. **不要为了测试而登出 Apple ID。** 已装好的侧载 App 在有效期内不受登录状态影响，
   登出反而会让本来能刷新的 App 变成彻底锁死。
3. **不要指望升级 Sideloadly 版本。** 0.71.1 已经是当前最新；修 `-80046` 需要换 anisette 来源，
   而 Sideloadly 把它做成了付费功能。

## 参考资料

- [iPASide CHANGELOG（1.2.9 修 -80046）](https://github.com/pwnapplehat/iPASide/blob/main/CHANGELOG.md)
- [Impactor issue #298：-80046 在 2FA 之后出现](https://github.com/claration/impactor/issues/298)
- [AltStore issue #1840：-80046](https://github.com/altstoreio/AltStore/issues/1840)
- [Apple GSA 503 与 client-info 的关系（AltStore PR #1790）](https://github.com/altstoreio/AltStore/pull/1790)
- [iloader / Sideloadly / Impactor 登录失败 429](https://onejailbreak.com/blog/iloader-sideloadly-impactor-apple-id-login-fail-429)
---

# 附录：App ID 创建失败（error 35 / appIdName）

登录通过之后，iPASide 走到 `Creating the App ID...` 会失败：

```
Sideload failed
35: An invalid value '燃烧的蔬菜3HD' was provided for the parameter 'appIdName'.
```

## 原因

Apple 的 `appIdName` 只接受 **ASCII 字母数字 + 空格**（≤50 字符）。
iPASide 的 `developer._sanitize_name()` 原本这样过滤：

```python
cleaned = "".join(ch for ch in name if ch.isalnum() or ch == " ").strip()
```

`str.isalnum()` 对 CJK 字符返回 **True** —— 它是「Unicode 字母数字」不是「ASCII 字母数字」。
所以中文原样穿过过滤器，Apple 直接拒掉整个 `addAppId.action` 调用。

```
>>> ''.join(c for c in '燃烧的蔬菜3HD' if c.isalnum() or c == ' ')
'燃烧的蔬菜3HD'
```

## 修法

`C:\Users\oo\AppData\Local\Programs\iPASide\engine\python\Lib\site-packages\ipaside_engine\developer.py`

```python
def _sanitize_name(name: str, fallback: str = "iPASide") -> str:
    cleaned = "".join(ch for ch in name if ch.isascii() and (ch.isalnum() or ch == " "))
    cleaned = " ".join(cleaned.split())[:50].strip()
    if len(cleaned) < 4:
        return fallback
    return cleaned
```

`add_app_id()` 传入 bundle id 末段作为 fallback：

```python
def add_app_id(team_id: str, bundle_id: str, name: str) -> dict[str, Any]:
    fallback = bundle_id.rsplit(".", 1)[-1] or "iPASide App"
    result = _request(
        "ios/addAppId.action",
        {"identifier": bundle_id, "name": _sanitize_name(name, fallback)},
        team_id=team_id,
    )
    return result.get("appId", {})
```

`len(cleaned) < 4` 这个阈值是必要的：`燃烧的蔬菜3HD` 过滤后只剩 `3HD`，
虽然能注册成功，但在开发者后台里读起来像乱码，不如回落到 `veggies3_4399`。

## 结果

```
App IDs registered: 1
  com.soco.veggies3_4399.HMT45URQAG   veggies3_4399

Sideloaded Veggies3HD (com.soco.veggies3_4399.HMT45URQAG).
  Uploading 72 / 72 MB · Installing · Postflighting · Sandboxing · Installed

com.soco.veggies3_4399.HMT45URQAG    Veggies3HD  [7 days left]
```

**装成功了。** 首次启动需要在 iPhone 上信任开发者：
`设置 → 通用 → VPN 与设备管理 → 开发者 App → 敬良 李 → 信任`。

## 顺带说明

- 免费账号无法注册真实 App Store 标识符（Apple 返回 9401），所以 iPASide 自动追加 team id：
  `com.soco.veggies3_4399` → `com.soco.veggies3_4399.HMT45URQAG`。这是正常的，不影响运行。
- 显示名仍然是中文（`CFBundleDisplayName` 没动），桌面上看到的还是「燃烧的蔬菜3HD」。
- `--name Veggies3HD` 只是绕过上面那个 bug 的临时手段；打完补丁后不带这个参数也能装。
- 这个补丁在 iPASide 重装后会被覆盖，需要重新打（备份在 `developer.py.bak-*`）。