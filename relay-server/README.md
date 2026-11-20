# AirSync 自建中转服务器

用于跨网（非同一局域网 / 蜂窝网络）在 Mac 与 Android 之间转发 AirSync WebSocket 加密帧的轻量级中继服务。

## 工作原理

- 客户端先发送握手帧：`{"op":"join","room":"<房间名>","secret":"<至少16位密钥>","role":"mac或android"}`。
- 服务器按 `(room, secret)` 隔离会话；业务帧在客户端侧已经过 AES-GCM 端到端加密，中转服务器仅做盲转发，不解密、不落盘、不缓存离线消息。
- 支持 `ping` / `pong` 保活、同角色新连接无缝接管旧连接、对端上下线通知（`peer`）以及 600 秒空房自动回收；单帧上限 8 MiB。
- 客户端中转模式下支持：应用列表同步、通知与快捷回复、通知清除、文本剪贴板、设备电量/媒体状态及控制；文件传输、投屏镜像与快传仍限于局域网直连模式。

## 部署建议（HTTPS / WSS）

建议通过 Nginx / Caddy 在 443 端口终止 TLS 并反向代理到 `127.0.0.1:8899`：

- **使用正规 CA 证书（推荐，如 Let's Encrypt）**：客户端「证书 SHA-256 指纹」留空即可，默认走系统信任链。
- **使用自签证书**：请将私钥严格保存在服务端，切勿提交到仓库；用下面命令计算叶证书 SHA-256 指纹并填入两端客户端设置中：
  ```bash
  openssl x509 -in relay.crt -noout -fingerprint -sha256
  ```

## 本地运行与回归测试

请使用 Conda Python 环境运行：

```bash
conda create -n airsync-relay python=3.12
conda run -n airsync-relay pip install -r requirements.txt
conda run -n airsync-relay python server.py
```

回归测试与双端联调测试：

```bash
conda run -n airsync-relay python test_regressions.py -v
conda run -n airsync-relay python test_relay.py ws://127.0.0.1:8899
```
