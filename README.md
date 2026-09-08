# DSH Remote — Android 客户端

将 DeepSeek Harness 的远程访问封装为原生 Android App。扫码/粘贴配对链接后即可在手机上远程访问电脑上的 DSH——手机与电脑共用同一份官方 Web GUI，由 `@linxin666/dsh-remote-web-ui`（0.3.x）插件注入竖屏触控适配。支持 App 版本更新与应用内升级。

## 功能

| 功能 | 说明 |
|------|------|
| **首次配对引导** | 第一次启动只有两个选项：**扫描配对二维码** 或 **输入配对链接**；配对链接由插件在浏览器内完成握手 |
| **远程访问** | 全屏 WebView 加载 DSH host 的官方 Web GUI（`/`），插件注入竖屏/触控适配层，复用全部能力（工作区、会话、聊天、模型、权限、审批等） |
| **配对与门禁** | 配对、落地、撤销、重开全部由插件闭环：未配对设备显示插件自带的双语重扫页；被撤销后 `/remote` 通道返回 403 |
| **友好错误页** | 主机连不上时显示中文错误界面，含「重试」与「重新配对」按钮，而非系统默认 WebView 报错页 |
| **加载动效** | 首次加载期间显示居中转圈动画，避免白屏闪烁 |
| **检测更新** | 启动时查询 GitHub 最新 release，有新版本时提示 |
| **应用内下载安装** | 点「去更新」→ 校验安装权限 → 下载 APK（带进度条）→ 拉起系统安装器完成升级 |

## 项目结构

```
app/src/main/java/com/dsh/remote/
  MainActivity.kt       主界面：全屏 WebView 壳 + 错误页/加载动效 + 更新下载安装
  OnboardingActivity.kt 首次配对引导页（扫描二维码 / 输入配对链接，二选一）
  PairingController.kt  配对链接解析（/pair-accept?pair=token）
  UpdateChecker.kt      检测更新、下载 APK
  ConfigStore.kt        配置持久化（host）
  SettingsActivity.kt   设置页：检查更新 + 版本
  RemoteApp.kt          Application 占位
```

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

启动 dsh web，并安装启用移动端插件 `@linxin666/dsh-remote-web-ui@latest`（0.3.x）。若需局域网访问，在插件设置卡片打开「局域网访问」，或以 `dsh web --host 0.0.0.0` 启动。

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

## 安全说明

- 远程访问依赖插件的 **QR 配对 + 可撤销设备会话** 门禁（一次性令牌 + 设备会话），只有已配对设备能访问。
- 配对、落地、门禁与重开逻辑全部由 `@linxin666/dsh-remote-web-ui` 插件在服务端实现；本 App 不持有任何凭据逻辑。
- 更新仅从你指定的 GitHub 仓库 release 下载，下载前有明确提示与安装权限校验。
