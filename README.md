# AirSync Android（中转增强 + 省电精简中文版）

## 4.3.2：移除媒体同步与播放器

此版本与 Mac 4.3.2 配套：移除两端的音乐状态、封面、播放/暂停、切歌、进度、媒体会话监听、播放器通知及媒体设置入口。保留通知、剪贴板、电量和独立的系统音量控制；不会更改配对密钥或通知访问授权。升级时清理旧的歌曲缓存和媒体偏好。

已授权的 ADB 设备自动接管同步：优先有线 USB，其次已连接且已授权的无线 ADB；没有可用 ADB 或连接失败时，恢复原先保存的局域网/中转方式。手机统一显示「ADB 直连」，不会开启或配对新的无线调试设备。

## 4.3.1：USB 自动直连与重连修复

与 Mac 4.3.1 配套使用：在已有 AES 配对和 USB 调试授权后，Mac 插线优先使用 USB 同步，手机显示「ADB 直连」；拔线自动恢复保存的局域网/中转设置。USB 是临时通道，不覆盖之前保存的连接方式、局域网地址或密钥。

USB 使用固定的手机回环地址 `127.0.0.1:16996`，由 Mac 的 ADB reverse 接到 Mac WebSocket 服务；普通应用不能调用 USB 切换入口，显式广播接收器要求系统 `android.permission.DUMP` 权限。无需额外 Root 授权，也不新增轮询或 WakeLock。

补齐握手/发送失败恢复、重复对端上线事件、Wi-Fi/蜂窝默认网络恢复，以及暂停后继续无法重连的问题。用户手动断开、暂停或关闭自动连接时，自动事件不会重新连接。通知与剪贴板同步设置保持原值。

Mac 内置镜像新增横竖屏窗口适应、透明度和双击关闭；视频经本地 ADB 通道，不通过中转服务器。编译与测试命令仍见下文第五节。

本仓库是基于开源项目 [sameerasw/airsync-android](https://github.com/sameerasw/airsync-android) 派生的**非官方修改版**，由 [funnykeke](https://github.com/funnykeke) 维护。

改造的核心目标非常明确：
1. **在原本局域网直连的基础上，新增自建服务器中转（Relay）连接**，不在同一 Wi-Fi / 切蜂窝流量时也能稳定同步通知、剪贴板和电量状态；
2. **大刀阔斧删掉多余、不常用、高耗电的功能模块与冗余依赖**；
3. **针对后台常驻做深度省电与低功耗改造**；
4. **全量简体中文化与自建中转 TLS 脱敏**。

---

## 一、双传输模式（局域网直连 + 服务器中转）

本版本在原有局域网直连之上新增了「服务器中转」传输模式，两端根据连接方式自动分层启用能力，兼顾跨网可用性与低功耗：

| 能力 | 局域网直连（Local） | 服务器中转（Relay） |
|---|---|---|
| 适用场景 | 手机与 Mac 处于同一 Wi-Fi | 跨网 / 蜂窝数据 / 公司网络隔离 |
| 通知同步、快捷回复、通知清除 | ✅ | ✅ |
| 双向文本剪贴板同步 | ✅ | ✅ |
| 应用列表与图标同步、单应用开关 | ✅ | ✅ |
| 电量与独立系统音量控制 | ✅ | ✅ |
| 文件传输、快传（Quick Share）、WebDAV 挂载、投屏镜像 | ✅ | ❌（刻意禁用，防止高耗电与中转带宽浪费） |
| 安全机制 | 局域网端到端 AES-GCM 加密 | 传输层 WSS（TLS）+ 业务帧端到端 AES-GCM 加密（中转端盲转发、不解密不落盘） |

- **灵活 TLS 校验**：源码不含任何硬编码个人服务器地址或自签证书。
  - 使用正规 CA 证书（如 Let's Encrypt 域名）：客户端「证书 SHA-256 指纹」留空即可，默认走 Android 系统信任链。
  - 使用无域名 IP 自签证书：在「设置 → 传输模式 → 证书 SHA-256 指纹」中填入服务端叶证书的 64 位 SHA-256 指纹即可启用指纹固定（Pinning）。

---

## 二、删减了哪些多余 / 不常用功能

为了把安装包体积、内存占用和后台唤醒降到最低，本版本从源码、资源、权限与依赖层面彻底清除了以下不常用功能：

### 1. 移除的功能模块
- **通话与联系人同步**：移除来电监听（`CallReceiver` / `CallStateCallback`）、通话记录观察者（`CallLogObserver`）、来电控制、联系人查询与头像解析，以及 `libphonenumber` 依赖。
- **蓝牙（BLE）发现与传输**：移除整个 `data/ble/` 蓝牙 GATT 服务端、广播与分包桥接，避免蓝牙持续扫描/广播耗电。
- **灵动岛与 Smartspacer**：移除 `island/`（AirSync Island 灵动岛常驻服务）和 `smartspacer/` 卡片集成。
- **笔记角色 / 内容捕获 / 锁屏远程控制**：移除 `NotesRoleManager`、`ContentCaptureManager`、`KeyguardHelper`、`MacRemoteActions` 与 `CREATE_NOTE` 入口。
- **壁纸同步与 Essentials 桥接**：移除壁纸同步逻辑与 `EssentialsRequestReceiver` 跨应用广播接收器。
- **引导页与推广组件**：移除欢迎引导页（`WelcomeScreen`）、应用内评分卡片（`RateAppCard`）、独立剪贴板聊天页（`ClipboardChatActivity`）、旧版在线帮助页与原作者社交/赞助推广入口。

### 2. 清理的冗余依赖与权限
- **移除依赖**：Room 数据库 + KSP 编译插件、Gson + Ktor Gson 序列化、`libphonenumber`、`coil-gif`、`runtime-livedata`、JitPack 仓库及 `ExceptionReport`（替换为零外部依赖的本地 [CrashHandler.kt](app/src/main/java/com/sameerasw/airsync/crash/CrashHandler.kt)）。
- **移除冗余权限**：清理 `BATTERY_STATS`、`READ_WALLPAPER_INTERNAL`、`DOWNLOAD_WITHOUT_NOTIFICATION`、`LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE`、`WAKE_LOCK`、`ESSENTIALS_AIRSYNC_BRIDGE` 等 6 项不再需要的权限。
- **瘦身效果**：裁剪 21 份无用 GIF/图标资源并默认仅打包 `arm64-v8a`（可通过 `-Pairsync.abi=all` 构建全架构），R8 压缩后 Release APK 约 **17 MB**。

---

## 三、做了哪些省电与后台优化

日常待机省电是本次改造的重点，主要从「消灭无效常驻服务」「按模式隔离扫描」「减少高频唤醒」三个层面入手：

1. **中转模式下彻底关闭局域网服务（核心省电）**：
   - 在 `relay` 模式下，`AirSyncService` 启动时**显式停止** `DiscoveryOrchestrator`（mDNS/UDP 局域网发现）、`WakeupService` 和 `WebDavServer`，仅保留单一中转长连接与前台保活服务（实机 `dumpsys activity services` 已验证中转模式下 `WakeupService` 不再常驻）。
   - 屏蔽中转模式下无意义的局域网 `NetworkCallback` 回调和前后台扫描触发。
2. **按需启停 ADB mDNS 发现**：
   - `MainActivity` 仅在局域网模式（`local`）下启动 ADB mDNS 与设备发现，且在 `onStop()` 离开界面时立即停止 `AdbDiscoveryHolder`，不再后台长期扫描。
   - `ClipboardActionActivity` 恢复时同样按传输模式门控，不在中转模式下误拉起局域网发现。
3. **减少无线网卡锁与连接风暴**：
   - `UdpDiscoveryBackend` 在 `PASSIVE`（被动）模式下主动释放 `WifiManager.MulticastLock`，防止长期阻止 Wi-Fi 进入低功耗休眠。
   - `SyncManager` 在中转模式下跳过不必要的 ADB 探测（省去 1 秒阻塞延迟）及 1.5 秒后的重复全量 `device` 状态包发送，并统一复用单例 `DataStoreManager`。
   - 桌面小组件（`AirSyncWidgetProvider`）将原本多次 `runBlocking` 读取合并为单次配置读取，降低刷新开销。
4. **全面中文化与原包名无痛升级**：
   - 界面、设置子页、小组件、快捷方式、磁贴、系统音量、快传通知与离线帮助页均已中文化（完整维护 `values/strings.xml` 与 `values-zh/strings.xml`）。
   - 保持原包名 `com.sameerasw.airsync`，自签名 Release 安装可原地覆盖升级并保留原有配置。

---

## 四、如何部署自建中转服务器

仓库内置了轻量化 Python 中转服务 [relay-server/](relay-server/)，仅需一台带公网 IP 的普通云服务器（最低 1 核 512M 即可稳定运行）+ Nginx 反向代理即可部署。

完整保姆级部署教程（包含 **Conda 环境搭建、systemd 后台守护、Nginx WSS 配置、Let's Encrypt 正规证书 / 无域名 IP 自签证书与指纹生成、防火墙与测试命令**）请直接查看：

👉 **[relay-server/README.md — AirSync 中转服务器部署指南](relay-server/README.md)**

快速接入流程一览：
1. 在服务器部署 `relay-server/server.py`（默认监听 `127.0.0.1:8899`），并用 Nginx/Caddy 在 443 端口配置 `wss://` 反向代理。
2. 在手机端与 Mac 端的「传输模式 / 连接设置」中切换为 **服务器中转**，填入相同的：
   - **服务器地址**：例如 `wss://relay.example.com/relay`（或 `wss://<服务器IP>/relay`）
   - **房间名（Room）**：自定义任意标识（如 `my-sync`）
   - **密钥（Secret）**：至少 16 位随机字符串（两端一致即可自动隔离他人）
   - **证书 SHA-256 指纹**：正规域名证书**留空**；自签证书填入 64 位 SHA-256 指纹。

---

## 五、本地编译与测试

需要 JDK 21 与 Android SDK：

```bash
# 运行全部单元测试并构建 Debug APK（默认 arm64-v8a）
./gradlew :app:testDebugUnitTest :app:assembleDebug

# 如需构建全架构 APK
./gradlew :app:assembleDebug -Pairsync.abi=all
```

如需构建自签名 Release 包，将 keystore 放在项目根目录 `airsync-self.keystore` 并配置 `signing.properties`（两者均已加入 `.gitignore`，切勿提交）：

```properties
storePassword=你的密钥库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 六、配套组件与开源协议

- **中转服务端**：[relay-server/README.md](relay-server/README.md)
- **macOS 客户端**：遵循上游 macOS 仓库附加条款（不公开分发修改版二进制），macOS 端修改版保留于私有仓库仅供个人自编译使用。
- **开源协议与致谢**：
  - 上游项目：[sameerasw/airsync-android](https://github.com/sameerasw/airsync-android)，完整保留原 MPL-2.0 协议与附加条款于 [LICENSE](LICENSE)。
  - 第三方组件声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)（含 ClipSync 致谢）。
  - 本仓库为非官方派生分支，与原作者无从属关系。
