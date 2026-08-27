# DSH Remote — Android 客户端

将 DeepSeek Harness 的手机端（PWA `/m/` 页面）封装为原生 Android App。扫码/粘贴配对后即可远程访问电脑上的 DSH，支持版本更新与应用内升级。

## 功能

| 功能 | 说明 |
|------|------|
| **首次配对引导** | 第一次启动只有两个选项：**扫描配对二维码** 或 **输入配对链接**；成功配对后直接进入远程访问 |
| **远程访问** | 全屏 WebView 加载 DSH host 的 `/m/` 独立移动端页面，复用全部能力（工作区、会话、聊天、模型、权限、审批等） |
| **配对失效自动回引导** | 启动时校验配对状态；cookie 失效/被吊销（含 HTTP 403）时自动回到配对引导页重新配对，不清空已保存配置 |
| **友好错误页** | 主机连不上时显示中文错误界面，含「重试」与「重新配对」按钮，而非系统默认 WebView 报错页 |
| **加载动效** | 配对校验/首次加载期间显示居中转圈动画，避免白屏闪烁 |
| **检测更新** | 启动时查询 GitHub 最新 release，有新版本时提示 |
| **应用内下载安装** | 点「去更新」→ 校验安装权限 → 下载 APK（带进度条）→ 拉起系统安装器完成升级 |
| **移动端增强**（服务端插件侧） | 长文本默认不自动收起 |

## 项目结构

```
app/src/main/java/com/dsh/remote/
  MainActivity.kt       主界面：全屏 WebView + 配对校验 + 错误页/加载动效 + 更新下载安装
  OnboardingActivity.kt 首次配对引导页（扫描二维码 / 输入配对链接，二选一）
  PairingController.kt  配对链接解析、/api/pair/accept、配对状态检测
  UpdateChecker.kt      检测更新、下载 APK
  ConfigStore.kt        配置持久化（host）
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

启动 dsh web，并安装启用移动端插件 `@linxin666/dsh-remote-web-ui`。默认监听如 `http://<电脑IP>:3080`。

### 2. 配对

第一次打开 App 会进入配对引导页，只有两个选项（二选一）：

- **扫描配对二维码**：调起相机，扫描桌面端远程面板（DSH Web 侧边栏「远程」入口）显示的 QR 码。
- **输入配对链接**：粘贴桌面端复制的配对链接（或纯配对令牌）。

配对成功后自动进入远程访问页面。此后每次打开 App 直接进入远程访问。

> 电脑关机、网络断开、cookie 过期或被服务端吊销时，App 会回到配对引导页重新配对，或显示「无法连接到主机」的友好错误页（可重试 / 重新配对）。

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

- 远程访问依赖插件原有的 **QR 配对 + 配对 cookie** 门禁，只有已配对设备能访问；配对被吊销后 App 会自动要求重新配对。
- 本 App **不含**电源/关机等高风险操作。
- 更新仅从你指定的 GitHub 仓库 release 下载，下载前有明确提示与安装权限校验。

## 移动端服务端插件（可选增强）

「长文本不自动收起」这项在服务端插件 `@linxin666/dsh-remote-web-ui` 侧实现，本仓库同时修改了插件产物与源码：

- 产物（当前运行即生效）：`node_modules/@linxin666/dsh-remote-web-ui/lib/mobile.js`
- 源码（未来重建保持一致）：`src/mobile/views/ChatView.tsx`、`src/mobile/mobile-styles.ts`

若你不使用这些增强，直接忽略即可，不影响 App 的配对与远程访问。
