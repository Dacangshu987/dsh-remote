# DSH Remote — Android 客户端

将 DeepSeek Harness 的远程访问封装为原生 Android App。扫码/粘贴配对链接后即可在手机上远程访问电脑上的 DSH——手机与电脑共用同一份官方 Web GUI，由 `@linxin666/dsh-remote-web-ui`（0.4.x）插件注入竖屏触控适配。支持 App 版本更新与应用内升级。

## 功能

| 功能 | 说明 |
|------|------|
| **首次配对引导** | 第一次启动只有两个选项：**扫描配对二维码** 或 **输入配对链接**；配对链接由插件在浏览器内完成握手 |
| **远程访问** | 全屏 WebView 加载 DSH host 的官方 Web GUI（`/`），插件注入竖屏/触控适配层，复用全部能力（工作区、会话、聊天、模型、权限、审批等） |
| **后台提醒** | 前台服务常驻连接：智能体**回复完成**、**等待审批/提问**、智能体出错、配对被撤销、主机离线、隧道/中继异常都会推送系统通知 |
| **在线状态保活** | 定时向 host 发送设备心跳，避免 App 退到后台后桌面面板把本机显示为「离线」 |
| **配对与门禁** | 配对、落地、撤销、重开全部由插件闭环：未配对设备显示插件自带的双语重扫页；被撤销后 `/remote` 通道返回 403 |
| **刷新页面** | 在 ⋯ 菜单里。原先的下拉刷新手势已移除——它与 GUI 自身的滚动/文本选择冲突（想选文字却触发刷新） |
| **网络恢复自动重连** | 手机从断网恢复（Wi-Fi/流量切换）时自动重新加载并提示，无需手动点「重试」 |
| **友好错误页 + 诊断** | 主机连不上时显示中文错误界面，并给出**具体原因**：当前主机地址、手机是否有网络、上次成功连接时间、主机是否可达、隧道/中继是否异常、配对是否被撤销；长按错误页可复制主机地址 |
| **分享进 App** | 从任意 App 分享文本（进剪贴板）或图片/文件（存入 App 目录）后自动打开 DSH |
| **主屏小组件** | 桌面小组件显示当前与主机的连接状态与更新时间 |
| **应用锁** | 可选：打开 App 需指纹/人脸/锁屏密码验证。配对凭据是 host 的完全控制凭据，此开关用于防止手机被他人短暂拿到 |
| **应用内选项** | 右上角一个半透明的 **⋯** 按钮：后台提醒开关、应用锁开关、刷新页面、**检查更新**、**重新扫码配对（切换主机）**。长按返回键也能打开（仅三键导航可用） |
| **手动检查更新** | ⋯ 菜单 →「检查更新」：有新版走更新弹窗，已是最新或检查失败都有明确提示。启动时的自动检查仍然静默，只在发现新版时提示 |
| **重新配对** | 已配对后 App 会直接进 GUI、不再显示配对页；选项里的「重新扫码配对」会清除主机地址与设备凭据并回到扫码页，用于更换主机地址（例如从公网中继切到隧道） |
| **加载动效** | 首次加载期间显示居中转圈动画，避免白屏闪烁 |
| **检测更新** | 启动时查询 GitHub 最新 release，有新版本时提示；也可从 ⋯ 菜单手动检查 |
| **应用内下载安装** | 点「去更新」→ 校验安装权限 → 下载 APK（带进度条）→ 拉起系统安装器完成升级 |

> **后台提醒的边界**：电脑关机或睡眠时提醒必然失效（App 只能推送「主机离线」）。Android 要求后台长连必须以前台服务形式存在，因此系统会显示一条低打扰的常驻通知；在隐藏选项里可以整体关闭后台提醒。

## 项目结构

```
app/src/main/java/com/dsh/remote/
  MainActivity.kt         主界面：WebView 壳 + 下拉刷新 + 网络重连 + 错误页/诊断 + 凭据捕获
  OnboardingActivity.kt   首次配对引导页（扫描二维码 / 输入配对链接，二选一）
  PairingController.kt    配对链接解析（/pair-accept?pair=token）
  UpdateChecker.kt        查询 GitHub release、下载 APK
  UpdateInstaller.kt      更新流程：安装权限校验 + 进度条下载 + 拉起安装器
  HostStatusClient.kt     读取 /api/pair/status，用于错误页诊断与看护告警
  ConfigStore.kt          配置持久化（host、上次成功连接时间）
  HostWatchService.kt     前台服务：心跳保活 + 订阅 $events + 连接看护告警
  MuxSocket.kt            手写 RFC 6455 客户端（不为一个 socket 引入 HTTP 依赖）
  AgentWatch.kt           把转发事件转成通知（轮次结束 / 出错 / 需要确认）
  NotificationHelper.kt   通知渠道与各类通知的唯一定义处
  HostWatchState.kt       小组件读取的连接状态快照
  WatchWidgetProvider.kt  主屏小组件
  ShareReceiverActivity.kt 接收 ACTION_SEND
  AppLockActivity.kt      应用锁（平台 BiometricPrompt + 锁屏密码回退）
  AppLockState.kt         应用锁开关与前台宽限期
  SecretStore.kt          设备凭据的 Keystore AES-GCM 加密存储
  RemoteApplication.kt    Application：进程启动时重置应用锁状态
```

App 没有自绘顶栏或设置页：界面就是一个全屏 WebView，更新检测在启动时自动进行，两个开关藏在长按返回键里。**配对状态不由 App 判定**——插件自带的 403 / 双语重扫页始终是权威，App 只把状态快照用于错误页措辞。

## 环境要求

- Android Studio（Hedgehog+）或命令行 Gradle
- JDK 17
- Android SDK：`compileSdk 34`，`minSdk 26`（Android 8.0+）
  - minSdk 26 的理由是仅用 adaptive-icon XML，无需二进制 PNG。如需支持 7.0，把 `minSdk` 改回 `24` 并提供 PNG 图标。

## 构建 APK

命令行方式：

```bash
cd <项目根目录>
./gradlew assembleDebug        # 产物: app/build/outputs/apk/debug/app-debug.apk
```

或用 Android Studio 直接打开根目录，等待 Gradle 同步后 Run `app`。

> 仓库未附带 `gradle-wrapper.jar`（生成环境无 Gradle 工具链）。`gradle-wrapper.properties` 已配置 Gradle 8.7；Android Studio 会提示自动下载 wrapper。若用纯命令行，先装 Gradle 8.7，再执行 `gradle wrapper` 生成 wrapper，或用已安装的 `gradle assembleDebug` 直接构建。

## 使用步骤

### 1. 运行 DSH host

启动 dsh web，并安装启用移动端插件 `@linxin666/dsh-remote-web-ui@latest`（0.4.x）。若需局域网访问，在插件设置卡片打开「局域网访问」，或以 `dsh web --host 0.0.0.0` 启动。

### 2. 配对

1. 在桌面 DSH Web 侧边栏点「远程访问」入口，面板铸造一枚一次性二维码。
2. 第一次打开 App 进入配对引导页，二选一：
   - **扫描配对二维码**：扫描桌面端面板显示的 QR 码。
   - **输入配对链接**：粘贴桌面端复制的配对链接（形如 `http://<host>:<port>/pair-accept?pair=<token>`）。
3. App 在 WebView 中打开配对链接，插件在浏览器内完成接受 → 落地 → 进入官方 Web GUI，竖屏适配生效。

此后每次打开 App 直接进入远程访问。电脑关机/网络断开时 App 显示「无法连接到主机」的友好错误页（可重试 / 重新配对）；配对被吊销时插件显示自带的双语重扫页，重新扫码即可。

## 更新升级

App 启动时会自动检查 [Dacangshu987/dsh-remote](https://github.com/Dacangshu987/dsh-remote) 的最新 release：

- 有新版本 → 弹窗提示当前/最新版本。
- 点「去更新」：
  1. 校验"安装未知来源应用"权限（Android 8+ 需要，可引导跳转系统设置开启）
  2. 解析 release 中的 `.apk` asset 并下载（显示进度条）
  3. 下载完成后拉起系统安装器完成升级
- 若 release 未附带 `.apk` asset，则退回打开 GitHub Release 页面。

> 发布新版本时，请把编译出的 APK 以 `.apk` 结尾命名并上传到对应 release 的 **Assets** 中，App 才能自动下载安装。

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 访问 DSH host 与 GitHub 更新 |
| `CAMERA` | 扫描配对二维码 |
| `REQUEST_INSTALL_PACKAGES` | 安装下载的更新 APK |
| `POST_NOTIFICATIONS` | Android 13+ 推送智能体提醒 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 后台保持与主机的连接（前台服务） |

## 安全说明

- 远程访问依赖插件的 **QR 配对 + 可撤销设备会话** 门禁（一次性令牌 + 设备会话），只有已配对设备能访问。
- 配对、落地、门禁与重开逻辑全部由 `@linxin666/dsh-remote-web-ui` 插件在服务端实现；本 App 不自绘配对界面，只负责解析链接与承载 WebView。
- **一个设备凭据是完全控制凭据**：它能读写 host 的工作区、执行工具、修改凭据。因此 App 把凭据用 Android Keystore 的 AES-GCM 加密后单独存储（`SecretStore`），并提供可选的**应用锁**（`AppLockActivity`）。
- 更新仅从你指定的 GitHub 仓库 release 下载，下载前有明确提示与安装权限校验。

## 后台提醒的实现依据

后台提醒依赖 DSH 自己的事件转发机制，实现时已逐项核对：

| 环节 | 依据 | 核对方式 |
|------|------|----------|
| 订阅通道 | `/remote/api/remote.mux`（唯一的 mux WebSocket） | 真机实测：`ws://` 与 `wss://`（公网中继）均握手成功并收到 `ready` 帧 |
| 订阅报文 | `{type:'open',streamId,endpoint:'$events',payload:{args:{}}}` | 真机实测收到 `ready` |
| 凭据传递 | WS 走 `?device=` 查询参数 | 真机实测通过 |
| 凭据来源 | `/pair-accept` 的 `Set-Cookie: dsh_pair=<deviceId>`（**Cookie 罐**） | 真机实测：`CookieManager` 可读，且值与 host 注册的设备 id 一致 |
| 事件名 | `api-session/status` / `api-session/error` / `api-session/added` | 来自 harness 的 `API_REMOTE_FORWARDED_EVENTS` 白名单声明 |
| 「回复完成」判据 | `api-session/status(sessionId, running)` 的 `true → false` 边沿 | 同上声明 |
| 心跳 | `POST /api/pair/heartbeat` | 实测：header 形式 **401**，Cookie `dsh_pair=<id>` 形式 **200**，且 host 的 `onlineCount` 0 → 1。App 使用 Cookie 形式 |
| 前台服务 | `FOREGROUND_SERVICE_DATA_SYNC` + 先建通知渠道 | 真机实测：服务前台运行，`isForeground=true` |

> 订阅是**只读**的：客户端从不回应 host 的 `waterfall` 请求（`approval/request`、`user-questions/request`），因此不会阻塞智能体——未回应的投递行为与「没有客户端在线」完全一致。
>
> 这些事件名属于 harness 的**内部协议**。若上游变更，App 会自动降级为只做心跳保活与连接看护（这两项只依赖公开接口），不会崩溃。

## 真机验证记录

在 Android 12（API 32）模拟器上做过一次完整的「全新安装 → 配对 → 后台常驻」回归，过程中发现并修掉了三个会导致**启动即闪退或功能静默失效**的真实缺陷：

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1 | **启动立刻闪退**（`SecurityException: … has android.permission.ACCESS_NETWORK_STATE`） | 网络监听与错误页诊断调用了 `ConnectivityManager`，但 manifest 未声明该权限 | 补 `ACCESS_NETWORK_STATE` |
| 2 | **闪退**（`CannotPostForegroundServiceNotificationException: Bad notification for startForeground`，伴随 `invalid channel … dsh_watch`） | 前台服务在通知渠道尚未建立时就 `startForeground`；渠道创建失败被静默吞掉，症状出现在离原因很远的地方 | `startForeground` 前显式 `ensureChannels()`，渠道创建失败改为记日志 |
| 3 | **后台提醒永不生效**（无报错、无崩溃） | 设备凭据实际存放在 WebView 的 **Cookie 罐**（`/pair-accept` 的 `Set-Cookie`），而不是 `localStorage['dsh-remote-device']`；按 localStorage 读取永远拿不到凭据 | 改为优先用 `CookieManager` 读取，localStorage 仅作回退 |
| 4 | **提醒通知被静默丢弃** | 通知渠道只在**前台服务启动时**创建；而 `AgentWatch` 可能先于服务发通知，向不存在的渠道投递会被系统静默丢弃 | `NotificationHelper.post()` 内先 `ensureChannels()`，并对「已抑制 / 已投递」记日志 |
| 5 | **开启应用锁后闪退**（用户报告） | **真凶是应用锁门禁本身**：锁住时 `MainActivity.onCreate` 在 `updates = UpdateInstaller(this)` **之前**就 `finish()`，而 `onDestroy` 仍会执行并读取这个 `lateinit` 属性 → `UninitializedPropertyAccessException` / `Unable to destroy activity`，直接杀进程 | `updates` 提前到门禁之前初始化；`onDestroy` 对 `updates`/`binding` 加 `isInitialized` 防护 |
| 6 | 应用锁在 API 28/29 上会崩（同类隐患，顺带修掉） | `BiometricPrompt.Builder.setAllowedAuthenticators()` 是 **API 30+** 才有的方法，代码却在 28+ 就调用（`NoSuchMethodError`）；`DEVICE_CREDENTIAL` 组合还会把提示切成全屏 keyguard 形态，该形态不允许 `setSubtitle` | 按 SDK 分支：28/29 只用 `BIOMETRIC_WEAK`，30+ 才加 `DEVICE_CREDENTIAL` 且只设标题 |
| 7 | **点「去系统设置」后 App 消失**（用户报告，看起来像闪退） | 两个原因叠加：① `AppLockActivity` 声明了 **`android:noHistory="true"`** —— 它一被覆盖就被销毁；② `openSecuritySettings()` 里还调了 `finishAndRemoveTask()`。而 `MainActivity` 在进锁屏时已经 `finish()`，于是任务被清空；从系统设置按返回就落到桌面，而不是回到锁屏 | 去掉 `noHistory`；「去系统设置」不再销毁任务；`ACTION_SECURITY_SETTINGS` 不可解析时回退到 `ACTION_SETTINGS` |
| 8 | **已设置锁屏仍报「去系统设置」**（vivo 实测，多机型适配问题） | 我只用 `BiometricManager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)` 做唯一判据。该 API 在 OEM 上不可靠：vivo 等机型对组合掩码返回失败，于是有锁屏的设备也被判成「没有凭据」 | 新增 `LockCapability`：按 **组合 → 仅生物 → 仅锁屏凭据 → `KeyguardManager.isKeyguardSecure` + 平台锁屏确认** 的顺序探测，任一可用即可；并区分「没配锁屏」与「配了但 API 不认」 |

> 第 3、4 条尤其值得记：它们都不报错、不崩溃，只是通知永远不出现 —— 真机验证是发现它们的唯一途径。
>
> 第 5 条的教训：**我上一轮猜错了方向**（以为是 `setAllowedAuthenticators` 的 API 级别问题），改完用户仍然闪退。最终靠 App 内建的崩溃记录器（`CrashLog` + `CrashReportActivity`）把堆栈直接显示在屏幕上才定位到真凶。没有可复现环境的崩溃，先让崩溃自己说话，比推理靠谱。
>
> 第 7 条的教训：它**根本不是崩溃**——没有 FATAL、没有异常，只是任务被清空后落到桌面。用户看到的「闪退」和日志里的「崩溃」是两回事；先确认到底有没有异常，再决定往哪个方向查。
>
> 第 8 条的教训：**能力检测不能只信一个 API**。`canAuthenticate` 在多家 OEM 上对组合掩码返回失败，用它当唯一判据会把「已设锁屏」误判成「没设」。现在的探测顺序以设备实际能用的最弱通道兜底，并且失败时把设备自报的能力写进提示框。

### 已验证 / 未验证

**已在此仓库实测通过**（Android 12 模拟器 + 真实 host）：

- 全新安装冷启动不闪退；UI 正常渲染
- 配对成功，设备在 host 侧注册；凭据经 `CookieManager` 读到并加密落盘
- `$events` 订阅在真机上握手成功并收到 `ready`（`ws://` 与 `wss://` 两条路径都测过）
- 前台服务运行（`isForeground=true`），心跳让 host 的 `onlineCount` 变为 1、`phase` 变为 `connected`
- 收到 `api-session/status` 的 `true → false` 边沿后，**系统通知成功投递**（`dumpsys` 可见 `NotificationRecord`，渠道 `dsh_alerts`，importance 3）
- ⋯ 选项按钮 → 菜单 → 「重新扫码配对」→ 确认框 → 回到配对页，主机地址与凭据均被清除
- **应用锁（第 5 条修复后复测）**：开启后重启 App → 停在 `AppLockActivity`、无 `FATAL`、无崩溃记录；本机未设锁屏时正确显示「无法验证」提示而非崩溃
- **去系统设置（第 7 条修复后复测）**：锁屏 →「去系统设置」→ 系统安全设置页打开（任务号 `t482` 保持不变）→ 按返回**回到锁屏页**（此前会落到桌面）
- **vivo 真机全功能验收通过**（用户实测）：配对、后台提醒、应用锁（第 8 条的多机型探测生效）、选项菜单均正常
- **多设备同时在线**：host 侧 `/api/pair/status` 显示 `onlineCount=2`，其中一台是 vivo V2183A —— 说明两台的 App 都在正常发心跳维持在线

**未验证**：

1. **真正由 host 发出事件的完整链路** —— 即「桌面跑完一轮对话 → 手机收到通知」。原因：模拟器的 WebView 经中继访问 GUI 时一直停在「重新连接中」，无法在 App 内发起对话；从 host 侧调用会话接口还需要 `_request` 参数描述符。事件名与参数形状取自 harness 的 `API_REMOTE_FORWARDED_EVENTS` 声明，且「边沿 → 通知」这一段已按同一形状实测通过。
2. **「重新连接中 / 工作区加载不出来」** —— 现象只出现在 MuMu 模拟器经中继访问时，且已确认不在 App 内（App 发出正确请求并拿到 101，之后被断开）。**vivo 真机上没有出现**，因此判断为宿主进程状态问题：重启 `dsh web` 后即恢复正常。第 5–8 条的排查过程与结论都记在上面。

> 顺带发现（**属宿主侧、非本 App 缺陷**）：中继 `dsh-market.com` 的 WebSocket 升级**必须带 `?device=` 凭据**，不给就被拒。实测无凭据升级失败、带凭据成功。若手机通过中继访问且 GUI 显示「重新连接中」，方向应指向插件/中继，而不是 App。
