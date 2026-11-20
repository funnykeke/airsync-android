# AirSync Android（省电精简中文修改版）

本仓库为基于开源项目 [sameerasw/airsync-android](https://github.com/sameerasw/airsync-android) 派生的**非官方省电精简修改版**，由 [funnykeke](https://github.com/funnykeke) 维护，面向个人日常低功耗使用场景。

## 主要修改内容

- **省电与精简**：移除通话/联系人同步、BLE 蓝牙发现、壁纸同步、笔记角色与远程控制等高耗电或不常用模块及冗余权限、依赖（移除 Room/KSP、Gson、libphonenumber、Coil GIF、JitPack 依赖等）。
- **局域网 + 自建中转双模式**：支持同 Wi-Fi 局域网直连（含快传、剪贴板、通知、媒体与文件访问）以及跨网自建中转模式（`relay-server/`）。中转模式下自动停止局域网广播/扫描、WakeupService、WebDAV 与 ADB mDNS 监听，显著降低待机耗电。
- **中转 TLS 脱敏与灵活配置**：支持标准 CA 证书（SHA-256 指纹留空走系统信任链）及可选的 64 位十六进制叶证书指纹固定（适用于自签证书），源码不含任何硬编码个人服务器地址或证书。
- **全面中文化**：界面、设置页、小组件、快捷方式、磁贴、通知通道、媒体控制、快传提示及离线帮助页均已中文化。
- **无痛升级兼容**：保持原包名 `com.sameerasw.airsync`，自签名安装后保留既有配置。

## 构建说明

- 需要 JDK 21 与 Android SDK。
- 默认构建 `arm64-v8a`（可通过 `-Pairsync.abi=all` 构建全架构）：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Release 签名请将本地 keystore 路径与密码配置在被 `.gitignore` 忽略的 `signing.properties` 中，切勿将签名密钥提交到仓库。

## 配套组件

- **自建中转服务**：见本仓库 [relay-server/README.md](relay-server/README.md)。
- **macOS 端**：遵循上游 macOS 仓库附加条款（不公开分发修改版二进制），macOS 端修改版源码保留在私有仓库中仅供自编译使用。

## 开源协议与致谢

- 原项目：[sameerasw/airsync-android](https://github.com/sameerasw/airsync-android)（MPL-2.0 协议及附加条款，完整保留于 [LICENSE](LICENSE)）。
- 第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)（含 ClipSync 致谢）。
- 本修改版为非官方派生分支，与原作者无从属关系。
