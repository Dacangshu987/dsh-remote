# DSH Remote Android 客户端 —— 新插件适配更新方案

> 目标：把仓库内这套针对旧插件（`@linxin666/dsh-remote-web-ui` ≤0.1.x，独立 `/m/` 移动端）写的 Android 客户端，适配到新插件（`0.3.18`，同一官方 Web GUI + 注入式竖屏适配）的契约。

---

## 1. 结论摘要

新插件（0.3.18）把「独立移动页 `/m/`」整个移除了。手机和电脑现在运行**同一份官方 Web GUI**：手机由插件在运行时注入竖屏触控适配层（`document.body.classList` 加 `dsh-remote-portrait`）。配对、门禁、落地页、重开全部由插件在**浏览器内**闭环完成。

这带来一个根本性的简化：**Android 客户端应当从「原生配对 + 原生 SSE 通知 + 自绘移动页」退化为一个薄 WebView 壳**。几乎全部旧有的原生逻辑（`PairingController` 的原生 accept/status、`EventMonitor` 的聊天事件 SSE、自定义 JS 桥、`/m/` 路由导航）都失去了存在意义。

---

## 2. 新旧契约对照（wire contract）

| 维度 | 旧（当前代码依赖） | 新（0.3.18） |
|---|---|---|
| 移动端页面 | `/m/` 独立移动页 | 无独立页；`/`（官方 GUI）+ 注入竖屏适配 |
| 配对链接 | `http://host/m/?pair=<token>[&workspace=…]` | `http://host/pair-accept?pair=<token>` |
| 配对 accept | `POST /api/pair/accept {token}` → 读 `Set-Cookie: dsh_pair` | `GET /pair-accept?pair=<token>` → 303 `/pair-app?device=<id>` + `Set-Cookie` |
| 落地页 | 直接 `loadUrl(host + "/m/")` | `/pair-app?device=<id>` 交付官方壳，`history.replaceState` 到 `/` |
| 设备凭据 Cookie | 固定 `dsh_pair` | **可配置** `cookieName`（值为 deviceId；`Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000`） |
| 无 Cookie 凭据 | 无 | `x-dsh-remote-device` 请求头（fetch）/ `device` 查询参数（WS）；storage key `dsh-remote-device` |
| 配对状态 | `GET /api/pair/status` → `{paired}` | `GET /api/pair/status` → `{ok, paired, requirePairingForLan, …}` |
| 配对状态 SSE | 无（旧用聊天 SSE） | `GET /api/pair/events` → `data: {type:'state',…}`（**仅回环**） |
| 聊天事件 SSE | `/m/api/events.mux`（turn/start、turn/end、approval/requested…） | **已移除**，无聊天事件流 |
| 撤销/失效 | 主帧 HTTP 403 → 回引导 | 门控 `/remote` 通道返回 403 JSON `{error:{code:"unpaired"}}`；未配对设备看到**插件自带**拦截页（含手动令牌输入） |
| 服务端更新 | 无（App 自己查 GitHub release） | 插件自带 `/api/update/*` + 侧栏一键自更新 `dsh-web` |
| JS 桥 | `DshAndroid.setRouteKind`、`__dsh_goBack`、`__dsh_navigateToSession` | 全部不需要（官方 GUI + 插件适配层接管） |

### 2.1 新配对往返（authoritative）

1. 桌面面板铸造令牌：`POST /api/pair/issue`（**仅回环**）→ `{ok:true, url, token, expiresAt, lanAddresses[], publicBaseUrl?}`，其中 `url = <base>/pair-accept?pair=<token>`。
2. 手机扫码得到 `<base>/pair-accept?pair=<token>`，在 WebView 里打开。
3. `GET /pair-accept?pair=<token>`：接受令牌（令牌在有效期内可重复使用）→ `Set-Cookie: <cookieName>=<deviceId>; …` → `303 /pair-app?device=<deviceId>`。
4. `GET /pair-app?device=<id>`：插件直接交付官方应用壳，注入捕获脚本（写 `sessionStorage/localStorage['dsh-remote-device']`、`history.replaceState('/')`、注册 `/pair-app.sw.js`）。
5. SPA 在 `/` 启动，竖屏适配层生效；后续 `/api` 走门控 `/remote` 通道（或回环直连）。

### 2.2 关键安全/行为事实（影响实现决策）

- 配对是 `/remote` 通道的访问控制；`requirePairingForLan` 默认开启，缺失/被撤销会话 → **HTTP 403 + `{error:{code:"unpaired"}}`**。
- 三个控制面**仅回环**：`/api/pair/*`、`/api/update/*`、`/api/plugin-manager/*`。
- 设备会话持久化到 `$DSH_HOME/remote-web-ui-devices.json`，`dsh web` 重启后 cookie 仍有效。
- 纯 HTTP 局域网源不是安全上下文，**重开 service worker 不注册**：局域网 HTTP 下重开会撞 harness 401，需重扫（插件已知限制，非 App 责任）。
- 未配对设备打开裸 `/`，插件会给出**双语重扫页**（而非 401 死路）——App 无需自绘「未配对」页。

---

## 3. 目标架构（推荐：薄 WebView 壳）

```
┌─────────────────────────────────────────────┐
│ Android App（只做三件事）                      │
│  1. 持久化 host base URL（ConfigStore）        │
│  2. 首次引导：扫桌面 QR / 粘贴配对链接           │
│     → 解析出 base + 完整 /pair-accept 链接      │
│  3. 全屏 WebView 加载 host（"/"），其余交给插件   │
└─────────────────────────────────────────────┘
        │ WebView 加载
        ▼
   DSH host（官方 Web GUI + 插件竖屏适配层）
   —— 配对、门禁、落地页、重开、更新全部在浏览器内闭环
```

App 不再维护：原生配对状态轮询、原生 SSE 聊天通知、自定义 JS 桥、`/m/` 路由栈。

> 备选方案 B（保留原生配对/状态/通知，重写新契约）不推荐：需要在原生侧复刻设备 cookie + `x-dsh-remote-device` 头 + `/pair-app` 落地链，既重复插件已有逻辑，又因契约（cookie 名可配置、403 形状、SSE 仅回环）脆弱。仅当有强需求「必须原生画配对 UI / 必须原生推送」时才考虑。

---

## 4. 分文件改动清单

### 4.1 保留（几乎不改）
- **`ConfigStore.kt`** —— 已简化为 `host` 单字段，符合目标。语义改为「存 host base URL」即可。
- **`RemoteApp.kt`** —— 保留。
- **`UpdateChecker.kt`** —— 保留用于 **APK 自更新**，但需确认仓库 `REPO` 常量是否仍指向 App 仓库（`Dacangshu987/dsh-remote`；插件仓库已迁到 `zhu1090093659/dsh-web`，那是**服务端**仓库，不是 APK 仓库）。

### 4.2 改写
- **`MainActivity.kt`**
  - `setupWebView()`：保留 JS/domStorage；**删除** `addJavascriptInterface("DshAndroid")` 与 `currentRouteKind`。
  - 加载目标：`host + "/"`（不再是 `/m/`）。
  - **删除** `verifyPairingThenLoad()` 里的原生 `PairingController.pairingStatus()` 预检——直接 `loadUrl("/")`，未配对由插件出拦截页。
  - **删除** `EventMonitor` 启动/生命周期（`onResume/onPause/onDestroy` 里的 eventMonitor 调用）。
  - **删除** `handleNotificationIntent` / `applyPendingSessionId` / `escapeJs` / `EXTRA_SESSION_ID`（依赖已删除的通知 + JS 桥）。
  - `handleSystemBack()`：改用 WebView 历史栈 `webView.canGoBack()/goBack()`，到顶 `moveTaskToBack(true)`。
  - 错误覆盖层 `errorView`：仅保留「主机连不上」场景（`onReceivedError` 主帧）；HTTP 403/未配对交给插件自带页面，App 不再自绘未配对页。
  - `requestNotificationPermission()`：删除（不再发通知）。

- **`OnboardingActivity.kt`**
  - 保留扫码 + 粘贴链接二选一（ZXing 仍需要）。
  - `handleLink()`：不再调用原生 `accept()`；改为把**完整配对链接**（`/pair-accept?pair=token`）交给 MainActivity 的 WebView 加载，同时保存解析出的 base origin 到 `ConfigStore`。
  - 即「解析链接 → 存 host → 回传完整 URL → WebView 打开」四步，插件在浏览器内完成 accept + reload。

- **`PairingController.kt`**
  - `parsePairLink()`：改为解析 `/pair-accept?pair=<token>`，抽出 `origin`（base）与完整 URL（或 token）。
  - **删除** `accept()`（插件浏览器内闭环）。
  - **删除** `pairingStatus()` 及其 `PairingStatus` 枚举（不再原生预检）。

### 4.3 删除
- **`EventMonitor.kt`** —— 依赖已消失的 `/m/api/events.mux` 聊天事件流；新 `/api/pair/events` 是仅回环的配对状态流，与聊天通知无关。整文件删除。
- **`NotificationPrefs.kt`** —— 仅被 EventMonitor/Settings 通知区使用，整文件删除。

### 4.4 简化（可选）
- **`SettingsActivity.kt`** —— 删除「通知设置」开关区（依赖 NotificationPrefs），只保留「检查更新 + 版本」。也可整体删除 SettingsActivity，因为插件的设置卡片已覆盖服务端配置；App 侧只保留更新检查入口即可。
- **`activity_settings.xml`** —— 删除通知设置 CardView 及对应 `switch*` 视图。
- **`strings.xml`** —— 删除 `notify_*`、`settings_notification_title`、以及旧引导/未配对相关已失效文案（`not_paired_*` 可删，因插件自带双语重扫页）。

### 4.5 Manifest / 依赖
- **`AndroidManifest.xml`**
  - 删除 `POST_NOTIFICATIONS` 权限（不再发通知）。
  - 若删除 SettingsActivity，同步移除其 `<activity>` 声明。
  - 保留 `INTERNET`、`CAMERA`（扫 QR）、`REQUEST_INSTALL_PACKAGES`（装 APK）。
- **`app/build.gradle.kts`** —— 无新依赖；若删除 ZXing 扫码（改用「粘贴链接」单一入口）才可移除 `zxing-android-embedded`，但推荐保留扫码。

---

## 5. 实施阶段

### 阶段 1：WebView 壳跑通（最小可用）
1. 改 `MainActivity` 加载 `/`，删除 JS 桥、EventMonitor、通知相关。
2. 改 `OnboardingActivity` + `PairingController.parsePairLink`，支持 `/pair-accept?pair=token`。
3. 手工验证：桌面开 `dsh web`（开局域网绑定）→ App 扫桌面 QR → WebView 完成配对并进入官方 GUI，竖屏适配生效。

### 阶段 2：清理死代码
4. 删除 `EventMonitor.kt`、`NotificationPrefs.kt`。
5. 简化 `SettingsActivity` / 布局 / 字符串 / Manifest 权限。

### 阶段 3：更新与回归
6. 确认 `UpdateChecker.REPO` 指向正确的 App APK 仓库（避免下载到服务端仓库的资产）。
7. 回归：断网重试、配对撤销后重开（应见插件双语重扫页）、横竖屏、深链接冷启动。

---

## 6. 需要你确认的决策点

1. **通知去留**：新插件已无聊天事件 SSE。手机上的「回复完成 / 待确认」系统通知无法再从旧通道获得。请确认：**直接砍掉**通知功能（推荐，符合插件新方向），还是需要另寻机制（如官方 GUI 自带的通知/轮询）重做？
2. **APK 分发仓库**：`UpdateChecker.REPO = "Dacangshu987/dsh-remote"` 是否仍是本 App 的发布仓库？插件服务端仓库已迁到 `zhu1090093659/dsh-web`，二者要区分开。
3. **设置页去留**：是否保留 App 内「设置/检查更新」页，还是彻底依赖插件设置卡片 + 系统更新？
4. **扫码 vs 粘贴**：是否保留 ZXing 扫码（需要 CAMERA 权限），还是只保留「粘贴配对链接」以进一步精简？

---

## 7. 验证清单（对齐插件官方 E2E）

- 回环 + 局域网绑定开 → 面板铸 QR → App 扫/贴 `/pair-accept?pair=…` → 进入官方 GUI，`document.body.classList` 含 `dsh-remote-portrait`。
- `GET /api/pair/status` 返回 `paired:true`（App 不必调用，仅作诊断）。
- 桌面「停止」后，App 下一次请求应命中插件 403/双语重扫页，而非 App 自绘错误。
- https（隧道/固定域名中继）下，App 杀进程重开应经 `/pair-app.sw.js` 直接回到应用；纯 HTTP 局域网下重开需重扫（已知限制）。
- 撤销设备后，`/remote` 通道返回 `{error:{code:"unpaired"}}`。

---

## 附：新插件关键端点速查

| 方法/路径 | 作用 | 门禁 |
|---|---|---|
| `POST /api/pair/issue` | 铸一次性令牌，返回配对链接 | 仅回环 |
| `GET /pair-accept?pair=…` | 接受令牌 → 303 `/pair-app` + Set-Cookie | 回环/LAN/公网 |
| `GET /pair-app?device=…` | 交付官方壳（注入设备捕获脚本） | 回环/LAN/公网 |
| `GET /pair-app.sw.js` | 重开 service worker（仅 https 源） | 回环/LAN/公网 |
| `GET /api/pair/status` | 返回 `{paired, requirePairingForLan, …}` | 回环/LAN/公网 |
| `GET /api/pair/events` | 桌面配对状态 SSE（`data:{type:'state'}`） | 仅回环 |
| `POST /api/pair/heartbeat` | 配对设备在线心跳（未配对 401 `unpaired`） | 回环/LAN/公网 |
| `POST /api/pair/revoke {deviceId}` | 撤销单设备 | 仅回环 |
| `POST /api/pair/stop` | 撤销全部 + 当前令牌 | 仅回环 |
| `GET /api/pair/lan-bind` | 局域网绑定状态 | 仅回环 |
| `/api/update/*` | `dsh-web` 服务端自更新 | 仅回环 |
| 门控 `/remote` 通道 | 配对设备的全量代理通道 | 需配对 cookie，403 `{error:{code:"unpaired"}}` |

> 参考来源：`@linxin666/dsh-remote-web-ui@0.3.18` 的 `README.zh.md`、`src/routes.ts`、`src/client/pair-api.ts`、`src/client/deep-link.ts`（仓库 `github.com/zhu1090093659/dsh-web`）。
