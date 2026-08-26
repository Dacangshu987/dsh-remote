# DSH Remote — Android 客户端

将 DeepSeek Harness 的手机端（PWA `/m/` 页面）封装为原生 Android App，并提供原生电源控制。

## 功能

| 功能 | 说明 |
|------|------|
| 远程聊天 | WebView 加载 DSH host 的 `/m/` 独立移动端页面，复用全部已有能力（工作区、会话、聊天、模型、权限、审批等） |
| **唤醒电脑（WoL）** | 原生 UDP 发送 magic packet，**二次确认**。电脑关机时仍可用（不依赖 DSH 在线） |
| **关机** | 通过 DSH 插件新增的配对端点 `mobile.shutdown` 让主机执行系统关机，**二次确认** |
| 文本不自动收起 | 长文本默认展开，不再 45vh 折叠（移动端产物已调高阈值） |
| **一键下拉到底** | 聊天界面出现悬浮「回到最新」按钮；向上翻阅时自动滚动暂停，点击回到底部 |

## 项目结构

```
app/src/main/java/com/dsh/remote/
  MainActivity.kt        WebView + 电源悬浮球（唤醒/关机，二次确认）
  SettingsActivity.kt    host 地址 / MAC / 广播地址配置
  PowerController.kt     WoL UDP + 关机 HTTP 实现
  ConfigStore.kt         SharedPreferences 配置存取
  RemoteApp.kt           Application 占位
```

## 环境要求

- Android Studio（Hedgehog+）或命令行 Gradle
- JDK 17
- Android SDK：`compileSdk 34`，`minSdk 26`（Android 8.0+）
  - minSdk 26 理由是仅用 adaptive-icon XML，无需二进制 PNG。如需支持 7.0，把 `minSdk` 改回 `24` 并提供 PNG 图标即可。

## 构建 APK

命令行方式：

```bash
cd <项目根目录>
./gradlew assembleDebug        # 产物: app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开根目录，等待 Gradle 同步后 Run `app`。

> 仓库未附带 `gradle-wrapper.jar`（本机无 Gradle 工具链，无法生成）。`gradle-wrapper.properties` 已配置 8.7；Android Studio 会提示自动下载 wrapper。若用纯命令行，先确保本机装了 Gradle 8.7，再执行 `gradle wrapper`。

## 使用步骤

### 1. 运行 DSH host

启动 dsh web，且**移动端插件已安装并启用**（`@linxin666/dsh-remote-web-ui`）。默认监听如 `http://<电脑IP>:3080`。

### 2. 配置 App

打开 App → 右上角「设置」，填写：

- **DSH 主机地址**：如 `http://192.168.1.100:3080`
- **电脑 MAC 地址（WoL）**：目标电脑网卡 MAC，如 `AA:BB:CC:DD:EE:FF`
- **局域网广播地址**：如 `192.168.1.255`（Windows: `ipconfig` 查`子网掩码`推算；或对 `0.0.0.0` / 目标 IP）

### 3. 配对

App 加载主机 `/m/` 页面后，按页面提示扫码/粘贴配对链接完成配对（复用插件原有 QR 配对流程，cookie 存于 WebView）。

### 4. 电源控制

点右下角电源悬浮球 → 选「唤醒电脑」或「关机」→ **再次确认**后执行。

- **唤醒**：由 App 直接向局域网广播 magic packet，不需要主机在线。
- **关机**：App 读取 WebView 中的配对 cookie，`POST /m/api/mobile.shutdown`；主机执行 `shutdown /s /t 0`（Windows）或 `shutdown -h now`（POSIX）。

## 前置条件（关机功能）

关机端点依赖插件已注入 `mobile.shutdown`。本仓库已修改好的插件产物位于：

- `node_modules/@linxin666/dsh-remote-web-ui/lib/index.js`（宿主端，新增 `mobile.shutdown` 分支）
- `node_modules/@linxin666/dsh-remote-web-ui/lib/mobile.js`（移动端，文本阈值 + 下拉到底）

若你发布/重新构建插件，请在源码侧同步保留这些改动（见下文「源码同步」）。

## 安全提示

- **关机是危险操作**：仅在 App 内做二次确认；服务端默认只接受来自**已配对设备**的请求（复用 `/m/api` 门禁）。
- WoL 广播只对局域网内开启 WoL 的目标机生效；请按需开启网卡/BIOS 的 Wake-on-LAN。
