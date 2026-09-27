# DSH Remote 功能更新方案（v2）

> 目标：在 **1.1.0 薄 WebView 壳**的基础上，为 App 补上 Web 侧**物理上做不到**的能力。
> 本文所有接口、事件名、限制都来自本机实际源码与运行实例，不是推测。

---

## 实施状态（已完成）

| 期 | 版本 | 状态 | 交付内容 |
|---|---|---|---|
| P1 | v1.2 稳固壳 | **已完成** | 下拉刷新、网络恢复自动重连、错误页诊断、长按复制主机 |
| P2 | v1.3 通知 | **已完成** | 前台服务 + 心跳保活 + 订阅 `$events`，推送「回复完成 / 待审批 / 出错 / 配对被撤销 / 主机离线 / 隧道中继异常」 |
| P3 | v1.4 入口 | **已完成** | 分享进 App、主屏小组件、隐藏选项入口 |
| P4 | v2.0 安全 | **已完成** | Keystore 加密凭据 + 应用锁（BiometricPrompt / 锁屏回退） |

落地时与原方案的差异、以及实测修正，见文末「实施记录」。下文保留原始设计作为决策依据。

---

## 0. 起手事实（已核实）

| 项 | 实测值 |
|---|---|
| App 现状 | 1.1.0 / versionCode 4；6 个 Kotlin 文件；依赖仅 core-ktx、appcompat、material、constraintlayout、ZXing |
| 目标插件 | `@linxin666/dsh-remote-web-ui` **0.4.2**（App 文档原先写 0.3.x，已在 P1 一并更正） |
| 运行实例 | `127.0.0.1:3080` 在跑，公网中继 `https://32a2e2e1a90e2d0b.dsh-market.com` + trycloudflare 隧道均 running |
| 已配对设备 | 4 台（3 台 Android WebView + 1 台 Windows Edge） |
| 插件已覆盖 | QR 配对、设备撤销、门禁、竖屏适配层、局域网绑定、防火墙、公网隧道、固定域名中继、`dsh-web` 自更新 |
| 手机适配层**主动隐藏**的功能 | SSH 终端、技能中心、任务看板、git graph、宠物、使用统计（`data-dsh-plugin` L2 语义根） |

### 0.1 一句话结论

**凡是「网页能做的」，插件都做完了。App 唯一不可替代的价值，是网页**物理上做不到**的四件事：系统通知、后台常驻、系统入口、设备本地安全。**

### 0.2 明确不做（避免和插件重复）

| 不做 | 理由 |
|---|---|
| 原生自绘聊天/会话界面 | 插件已确立「不存在会漂移的第二套界面」，自绘必然与官方 GUI 功能不同步 |
| 原生配对/门禁/凭据逻辑 | 插件在服务端闭环，App 复刻会因 `cookieName` 可配置、grant 一次性等契约而脆弱 |
| 原生画二维码 / 配对面板 | 桌面面板已有一键铸造；手机端只需要「扫」或「贴」 |
| 服务端自更新 | 插件已有 `/api/update/*` + 侧栏一键更新，且那三个控制面**仅限本机**，手机天然够不着 |

---

## 1. 关键发现：通知应当怎么做

**先给结论：1.1.0 删掉通知是对的，而且旧实现无法恢复。**

- 旧 `EventMonitor.kt`（246 行，见 commit `b9fe65d`）读的是 `/m/api/events.mux` 聊天 SSE，该端点在新插件中**已彻底移除**，所以「恢复旧通知」不是选项。
- 但**今天有一条更干净的新通路**。实测 DSH 源码：

  - 网关提供**转发事件流**：`REMOTE_EVENT_STREAM_ENDPOINT = "$events"`，挂在唯一的多路复用 WebSocket `/api/remote.mux` 上（手机侧镜像为 **`/remote/api/remote.mux`**）。
  - 线上帧类型是稳定的：`ready` / `emit` / `waterfall` / `cancel`（见 `dsh-api-gateway/lib/types/stream-protocol.d.ts`）。
  - 会话侧有**持久事件**：`turn/start`、`turn/end`（`dsh-session/lib/types/types.d.ts`）。
  - 审批侧有**持久事件**：`approval/asked`、`approval/decided`（`dsh-user-approval`）。后者正是「需要你点确认」的时刻。

- 门禁凭据 App 自己就拿得到：通道接受 `x-dsh-remote-device` 头 / WS 的 `device` 查询参数；设备 id 就存在页面 `localStorage['dsh-remote-device']`（`APP_DEVICE_STORAGE_KEY`）。

**所以：一个原生前台服务可以独立订阅 `$events`，在 App 退到后台甚至被杀后，仍然推送「轮次结束」「等待审批」通知。** 这是本次方案的核心，也是唯一只有原生 App 能做的事。

### 1.1 硬约束（必须写进方案，别让用户误以为能"电脑关机也提醒"）

| 约束 | 说明 |
|---|---|
| 宿主必须在线 | 电脑关机/睡眠 = 通知彻底失效。App 只能推送「宿主离线」本身 |
| Android 前台服务 | 后台长连必须前台服务常驻 → **常驻通知**，且需 `FOREGROUND_SERVICE`，Android 14+ 还要声明 `dataSync` 类型 |
| Android 13+ 权限 | 需 `POST_NOTIFICATIONS` 运行时授权（1.1.0 已无此权限） |
| 厂商省电 | 小米/华为/OPPO 需引导用户加白名单，否则服务被清 |
| 协议稳定性 | `$events` 是**未公开的传输协议**，随 DSH 升级可能变。必须做「探测失败 → 自动降级为轮询/静默」 |

### 1.2 分层降级策略（保证不空转）

| 层级 | 信号源 | 可靠度 | 用途 |
|---|---|---|---|
| L1 | 前台服务 + `/remote/api/remote.mux` 订阅 `$events` | 高（需协议探测） | 主力：轮次结束 / 待审批 |
| L2 | 前台服务定时 `POST /api/pair/heartbeat` + `GET /api/pair/status` | 高（**公开契约**） | 连接看护、宿主离线/恢复、隧道/中继异常 |
| L3 | WebView 注入 `addJavascriptInterface` 桥 | 中（依赖语义后缀） | App 在前台时补足文案与跳转定位 |
| L4 | `WebViewClient.shouldInterceptRequest` 观察 mux 升级/断开 | 高（框架级） | 无需解析协议即可判断连接死活 |

> L2/L4 只依赖公开契约与框架回调，即使 `$events` 协议变了，通知也不会完全失效。

---

## 2. 候选功能清单（按性价比排序）

| # | 功能 | 归属 | 价值 | 成本 | 建议 |
|---|---|---|---|---|---|
| 1 | 轮次结束 / 待审批系统通知 | 原生独有 | ★★★★★ | 高 | **做（旗舰）** |
| 2 | 前台服务保活 + 连接看护告警 | 原生独有 | ★★★★★ | 中 | **做** |
| 3 | 下拉刷新 + 自动重连 + 网络切换重试 | 原生独有 | ★★★★☆ | 低 | **做** |
| 4 | 配对摩擦优化（剪贴板识别/历史 host/重扫直入） | 原生独有 | ★★★★☆ | 低 | **做** |
| 5 | 分享进 App（文本/图片/文件 → 交给 agent） | 原生独有 | ★★★★☆ | 中 | 做 |
| 6 | 主屏小组件 / 快捷方式 / 角标 | 原生独有 | ★★★☆☆ | 中 | 做 |
| 7 | 本地锁（生物识别/PIN） | 原生独有 | ★★★★☆ | 中 | 做（安全，见 P4） |
| 8 | 语音输入 | 原生独有 | ★★★☆☆ | 低 | 做（复用系统 IME，不做自研） |
| 9 | 多主机配置槽 | 原生独有 | ★★☆☆☆ | 中 | 暂缓（当前单机场景） |
| 10 | 离线缓存/断网只读 | 半原生 | ★★☆☆☆ | 高 | 不做（service worker 已管 https 重开） |
| 11 | 用量/开销看板 | 网页已隐藏 | ★★☆☆☆ | 中 | 暂缓 |

---

## 3. 分期实施

### P1 — v1.2「稳固壳」（零风险，先落收益）

不引入任何权限与服务，纯打磨，先把当前体验的毛刺磨掉。

| 改动 | 文件 | 说明 |
|---|---|---|
| 下拉刷新 | `MainActivity.kt` + `activity_main.xml` | `SwipeRefreshLayout` 包 WebView（需显式加 `androidx.swiperefreshlayout` 依赖）；`onPageFinished` 停转 |
| 自动重连 | `MainActivity.kt` | `ConnectivityManager.NetworkCallback`：网络恢复即自动 `reload()`，替代手动点「重试」 |
| 错误页信息升级 | `MainActivity.kt` + `strings.xml` | 错误页显示当前 host、中继/隧道状态（来自 `GET /api/pair/status`）、上次成功加载时间 |
| 宿主状态诊断 | 新增 `HostStatusClient.kt` | 封装 `GET /api/pair/status`：`phase`（`lan-required`/`stopped`/`waiting`/`connected`/`disconnected`）、`tunnel`、`relay`、`posture`、`deviceCount`/`onlineCount` |
| 连接死活检测 | `MainActivity.kt` | `shouldInterceptRequest` 观察 `/remote/api/remote.mux` 升级与断开，作为「宿主在线」的权威信号 |
| 长按复制 host | `MainActivity.kt` | 便于反馈问题 |

**验收**：断网重连自动恢复；错误页能说清"是宿主挂了还是网络不通"。

---

### P2 — v1.3「通知」（旗舰，分两小步走）

#### v1.3.0 前台服务 + 连接看护（只依赖公开契约，先上）

1. **凭据获取**：页面登录完成后，注入脚本读 `localStorage['dsh-remote-device']` → 经 `addJavascriptInterface` 交给原生 → 写入 `EncryptedSharedPreferences`（P4 前先用普通私有 prefs，见决策点 D4）。
2. **`HostWatchService`**（前台服务，`foregroundServiceType="dataSync"`）：
   - 定时 `POST /api/pair/heartbeat`（插件客户端即 10s 一次）——**顺带解决一个真问题：App 退到后台时，桌面徽标会误显示"设备离线"，宠物也会被恢复。原生心跳能让宿主侧维持正确的在线状态。**
   - 定时 `GET /api/pair/status`，对变化发通知：宿主离线/恢复、隧道 `failed`、中继 `failed`、`/api` 暴露姿态告警（`posture.hosts[].exposed`）、设备被撤销（心跳 401）。
3. **通知渠道**：`NotificationChannel`（连接类低打扰 / 通知类默认）。
4. **权限**：manifest 加 `POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`；首启按需请求，用户拒绝则自动退回「仅前台」模式。

#### v1.3.1 轮次 / 审批通知（需协议探测）

1. **协议探测步骤（先做，再决定）**：用 OkHttp/自研 WS 客户端连 `/remote/api/remote.mux?device=<id>`，发 `{type:'open', streamId, endpoint:'$events', payload:{args:{}}}`，观察是否收到 `{type:'ready'}`。
   - **收到 ready** → 进入 2。
   - **未收到/被拒** → 停在 v1.3.0 的能力，并如实告知用户「后台轮次提醒暂不可用」，不要假装成功。
2. **订阅与触发**：解析 `emit` 帧的 `event` 名。
   - `turn/end` → 「✅ 回复完成」通知，正文取该会话最后一条 assistant 文本的截断。
   - `approval/asked` → 「⚠️ 等待确认」高优先级通知，带**可操作按钮**「去处理」直达该会话。
   - 会话标题：`ApiSessionList.list()` 返回按活跃度排序的 `SessionSummary`，用于通知标题。
3. **防打扰**：App 在前台时不发通知；同一会话 5s 去抖；会话级静音开关；`turn/end` 但无新 assistant 内容时不发。
4. **跳转定位**：通知携带 `sessionId`，点击后 `MainActivity` 打开会话。**注意**：官方 GUI 的会话深链路由不属于公开契约，需用 L3 注入桥或 `sessionStorage` 约定实现；若不可行则降级为「仅打开 App 到当前会话」，不要伪造深链。

**验收**：App 退后台 → 桌面发一条消息 → 手机收到「回复完成」；桌面触发一次审批 → 手机收到「等待确认」并可直接跳转处理。

---

### P3 — v1.4「入口」（把 App 变成手机上的第一等公民）

| 功能 | 实现要点 |
|---|---|
| **分享进 App** | `ACTION_SEND`（`text/plain` + `image/*` + `*/*`）接收 activity：文本→塞 composer；文件→走官方附件上传。**未知点**：塞 composer 需要 DOM 注入，需先做一次真实页面的选择器勘探；退路为「复制到剪贴板 + 打开 App + 提示粘贴」 |
| **语音输入** | 不自研：在 WebView 上方提供一个调用系统 IME 语音键的入口，或短按浮层用 `RecognizerIntent` 识别后注入。优先前者（零权限） |
| **配对摩擦优化** | 冷启动检测剪贴板是否含 `/pair-accept?pair=`，命中则顶部提示「检测到配对链接，一键配对」；保存最近 host 便于「重新配对」直入；撤销后重扫后自动恢复原 host |
| **主屏小组件** | `AppWidgetProvider` 展示：宿主在线/离线、`onlineCount`、最近一次 `turn/end` 时间。数据由 `HostWatchService` 缓存 |
| **快捷方式 / 角标** | 长按图标：新建会话 / 重扫配对 / 强制桌面模式（`sessionStorage.dsh-remote-force-desktop = 1`，插件支持的手动退出适配层开关，值得做成菜单项） |

---

### P4 — v2.0「安全」（当前最被低估的风险）

**风险陈述**：配对设备是**完全控制凭据**（可读工作区、执行工具、改凭据）。而 1.1.0 把设备凭据与全部会话数据存在 WebView 默认存储里——**手机被他人短暂拿到，等于交出电脑上的 DSH**。

| 功能 | 说明 |
|---|---|
| 本地锁 | `BiometricPrompt`（`androidx.biometric`）+ 设备凭据回退；冷启动与从后台返回超时后要求验证 |
| 凭据加密 | 设备 id 存 `EncryptedSharedPreferences`（`androidx.security-crypto`），不再依赖 WebView 明文存储 |
| 一键撤销 | 设置内「远程下线」：调 `POST /api/pair/revoke`（仅限回环——**注意此端点从手机够不着**，需改为引导用户在桌面面板撤销，或经门禁通道调用） |
| 敏感操作二次确认 | 通知里的「去处理」按钮不直接执行审批，只跳转（审批必须在 GUI 内完成） |

---

## 4. 需要新增的依赖

| 依赖 | 用途 | 备注 |
|---|---|---|
| `com.squareup.okhttp3:okhttp` | WS 客户端 + 心跳/状态轮询 | **推荐直接引入**。零依赖替代方案 `java.net.http.WebSocket` 在 Android 上要 **API 34+**，而本项目 `minSdk = 26`，不可用 |
| `androidx.security:security-crypto` | 凭据加密 | P4。注意：1.1.0 起 `EncryptedSharedPreferences` 已被标记 deprecated 且官方无稳定替代，届时需评估（可用 Keystore + 自管 AES 替代） |
| `androidx.biometric:biometric` | 本地锁 | P4 |
| `androidx.swiperefreshlayout:swiperefreshlayout` | 下拉刷新 | P1。需**显式声明**（不要依赖 material 的传递依赖，版本不可控） |
| `androidx.core:core-ktx` 已含 | `NotificationCompat` | 已有 |

**仍需保留**：`com.journeyapps:zxing-android-embedded`（扫码）、`androidx.constraintlayout`、`com.google.android.material`。

---

## 5. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| `$events` 非公开协议，DSH 升级即变 | 轮次通知失效 | 探测失败静默降级到 L2；版本兼容表写入 README |
| 前台服务被厂商省电清理 | 后台通知消失 | 首启引导加白名单；服务自恢复；UI 明示服务状态 |
| 常驻通知惹人烦 | 用户卸载 | 通知渠道设为「低打扰」且不可关闭地合并为一条连接状态；提供「仅前台」模式 |
| 凭据落到 prefs | 安全面扩大 | P4 加密 + 本地锁；期间 UI 明示风险 |
| DOM 注入依赖官方类名 | 分享/跳转失效 | 沿用插件的**语义后缀**策略（`[class$="_composerSeat"]`），并按插件 README 的建议每次官方 GUI 升级做一轮 QA |

---

## 6. 验证清单

- [ ] 冷启动扫 QR → 进官方 GUI，`document.body.classList` 含 `dsh-remote-portrait`
- [ ] 桌面「停止」后手机请求命中插件 403 / 双语重扫页，而非 App 自绘错误
- [ ] App 退后台，桌面发消息 → 收到「回复完成」通知；App 在前台 → 不打扰
- [ ] 桌面触发审批 → 收到「等待确认」，点击可处理
- [ ] App 退后台时，桌面面板设备行**仍显示在线**（心跳生效，宠物不被错误恢复）
- [ ] 电脑断网/关机 → 收到「宿主离线」；恢复后自动重连并提示
- [ ] 隧道 `failed` / 中继 `failed` / `/api` 暴露姿态告警 → 各自成一条通知
- [ ] 手机切 Wi-Fi↔流量 → 自动重连，无白屏
- [ ] 推送后台跑 8 小时，观察耗电与厂商清理情况
- [ ] 通知权限被拒 → 功能优雅降级，不崩、不静默失败

---

## 7. 需要你确认的决策点

1. **通知的代价你接受吗？** 后台提醒必须挂一个**常驻通知**（Android 硬性要求）并有一定耗电。选项：(a) 全程开启（推荐，功能最完整）；(b) 只在充电/连接 Wi-Fi 时保活；(c) 只做前台通知，不要常驻服务。
2. **是否接受为通知引入 `$events` 私有协议依赖？** 它是当前唯一能拿到「轮次结束」的通路，但属于 DSH 内部协议，升级可能失效。备选是只用公开的 `/api/pair/status` 做连接看护（稳，但**拿不到"回复完成"**）。
3. **推荐顺序**：先上 P1（v1.2 稳固壳）拿确定收益，还是直接冲 P2 通知？我建议 **P1 → P2**，因为 P1 能让 P2 的调试有可靠的地基（断网、错误页、状态诊断）。
4. **P4 安全是否提前？** 若你的手机有他人接触风险，建议把「本地锁 + 凭据加密」提到 v1.3 之前。
5. **多主机**（家里/公司两台电脑切换）现在需要吗？需要的话我在 P1 一并把 `ConfigStore` 改成多槽，避免二次返工。

---

## 8. 实施记录（P1–P4 已落地）

### 8.1 决策点的落地选择

| 决策点 | 方案里的选项 | 实际选择与理由 |
|---|---|---|
| 通知的代价 | 全程 / 仅充电 / 仅前台 | **全程开启 + 可关闭**。首次启动弹一次知情同意说明常驻通知与耗电，用户可关闭；关闭后服务停止，不再打扰 |
| 是否依赖私有协议 | 是 / 否 | **依赖，但只读且可降级**。订阅不回应任何 `waterfall`，协议失效时只损失「回复完成」，心跳与看护继续工作 |
| 实施顺序 | P1 先行 | **按 P1 → P2 → P3 → P4 全部完成** |
| P4 是否提前 | 是 / 否 | **作为 P4 完成**，但凭据加密（`SecretStore`）随 P2 一起落地，因为后台服务必须能解密凭据 |
| 多主机 | 现在做 / 暂缓 | **暂缓**。当前是单机场景，多槽会扩大 P2 的服务选择逻辑；留作后续 |

### 8.2 与原方案的差异

| 原方案 | 实际实现 | 原因 |
|---|---|---|
| 用 OkHttp 做 WS 客户端 | **手写 RFC 6455**（`MuxSocket.kt`） | 只需要一个 socket，不值得引入 HTTP 栈；实测握手与 `ready` 帧均通过 |
| `androidx.biometric` + `security-crypto` | **平台 `BiometricPrompt` + Keystore AES-GCM** | `androidx.security-crypto` 已被官方弃用且无稳定替代；平台 API 够用，还少两个依赖 |
| 添加 `lifecycle-runtime-ktx` | 未添加 | appcompat 已传递提供 `lifecycleScope` |
| 「轮次结束」= `turn/end` 事件 | **`api-session/status` 的 `running: true → false`** | `turn/end` 不在转发白名单里；`api-session/status` 在，且是现成的边沿信号 |
| 依赖 L3「WebView 注入 JS 桥」做前台补足 | 未采用 | 前台通知没有必要；省掉了一条依赖官方 DOM 类名的脆弱路径 |
| 分享文本注入 composer | **复制到剪贴板 + 打开 App** | 注入 composer 依赖官方类名，且失败会静默吞掉内容；剪贴板是诚实的降级 |
| 通知点击深链到具体会话 | **只打开 App 并提示会话号** | 会话深链不属于任何公开契约，不伪造跳转 |
| 主屏角标 / 快捷方式 | 未实现 | 需要额外权限或更多脆弱的入口约定，收益低于成本 |

### 8.3 实测修正（重要）

- **心跳必须用 Cookie，不能用 header。** 对运行中的 host 实测：`x-dsh-remote-device` 头 → **401**；`Cookie: dsh_pair=<id>` → **200**，且 `/api/pair/status` 的 `onlineCount` 从 `0` 变 `1`、`phase` 从 `disconnected` 变 `connected`。原因是插件的 `gate.js` 读的是 `readCookie(headers.cookie, config.cookieName)`。App 使用 Cookie 形式，并注明 `cookieName` 可被用户在插件设置里改名。
- **订阅报文必须精确匹配** `{args:{}}`：网关对 `$events` 校验「恰好一个 `args` 键且为空对象」，多余字段会被 `gateway/arguments-invalid` 拒绝。
- **未回应 `waterfall` 不会阻塞智能体**：服务端 `receiveRemoteEventResult` 只在收到结果时才结算，投递悬空等价于「没有客户端在线」，因此只读订阅是安全的。

### 8.4 尚未经端到端验证的部分（需要你在真机上确认）

代码通过了 `clean assembleDebug assembleRelease` 与 `lintVital`（No issues found），协议形状已在真机 host 上实测，但以下**行为**只做了静态推理，没有真机跑通：

- `api-session/status` 事件在真实一轮对话中的实际触发（我用 `dsh --profile headless` 触发时因 profile 不同，事件没有到达 web 端的网关）。
- 应用锁在 API 26/27 上会走「提示去系统设置」的分支（没有平台 BiometricPrompt）。
- 厂商省电策略对前台服务的实际影响。
- 分享图片/文件后的附件选择流程（文件已存到 App 目录，但把它送进官方 composer 仍需手动选择）。

### 8.5 验证清单状态

- [x] 构建与 lint 通过（debug + release，零缓存全量重建）
- [x] 订阅通道实测：101 升级 + `ready` 帧
- [x] 心跳实测：Cookie 形式 200，host `onlineCount` 0 → 1
- [x] 字符串引用零悬空 / 零未使用
- [x] 合并后 manifest 组件齐全（4 activity + 1 service + 1 receiver + 2 provider）
- [ ] 真机：App 退后台 → 桌面发消息 → 收到「回复完成」
- [ ] 真机：桌面触发审批 → 收到「等待确认」
- [ ] 真机：后台跑 8 小时观察耗电与厂商清理
