# AirSync 自建中转服务器部署指南

`relay-server` 是为 AirSync 定制的轻量级 WebSocket 盲转发中继服务（单文件 Python 实现，依赖极简），用于让 Mac 和 Android 在**非同一局域网 / 蜂窝网络 / 公司网络隔离**环境下也能保持低功耗连接。

---

## 一、工作原理与安全设计

- **盲转发、零明文**：AirSync 的业务数据在手机和 Mac 两端已经完成 **AES-256-GCM 端到端加密**，中转服务器只做原样字节转发，**不解密、不保存、不缓存离线消息**。
- **房间 + 密钥双隔离**：客户端连接后首帧发送握手包：
  ```json
  {"op": "join", "room": "<房间名>", "secret": "<至少16位密钥>", "role": "mac 或 android"}
  ```
  服务器按 `(room, secret)` 隔离会话，每个房间容纳 1 个 `mac` 与 1 个 `android`。
- **稳定重连与资源回收**：
  - 同角色新连接自动无缝接管旧连接，旧连接断开不会误报离线；
  - 支持 `ping` / `pong` 保活（默认 30 秒心跳、60 秒超时）及对端上下线通知（`peer`）；
  - 单帧默认上限 **8 MiB**，空房默认 **600 秒**后自动回收内存。

### 可选环境变量

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `RELAY_HOST` | `127.0.0.1` | 监听地址（建议仅监听本机，由 Nginx 暴露 443） |
| `RELAY_PORT` | `8899` | 监听端口 |
| `RELAY_ROOM_TTL` | `600` | 空房间保留时间（秒） |
| `RELAY_JOIN_TIMEOUT` | `15` | 建连后握手超时时间（秒） |
| `RELAY_MAX_MESSAGE` | `8388608` | 单条 WebSocket 帧大小上限（字节，默认 8 MiB） |

---

## 二、服务器部署步骤（Linux + Conda + systemd + Nginx）

任意 1 核 512M 以上的 Linux 云服务器（Ubuntu / Debian 等）即可流畅运行。

### 第 1 步：上传代码并创建 Conda 环境

```bash
sudo mkdir -p /opt/airsync-relay
# 将本目录的 server.py 和 requirements.txt 上传或复制到 /opt/airsync-relay/
sudo cp server.py requirements.txt /opt/airsync-relay/

# 创建独立的 conda Python 环境并安装 websockets 依赖
conda create -y -n airsync-relay python=3.12
conda run -n airsync-relay pip install -r /opt/airsync-relay/requirements.txt
```

确认 conda 环境中的 Python 路径（下一步配置 systemd 时会用到）：

```bash
conda run -n airsync-relay which python
# 示例输出：/home/ubuntu/miniforge3/envs/airsync-relay/bin/python
```

### 第 2 步：配置 systemd 后台常驻与开机自启

新建 `/etc/systemd/system/airsync-relay.service`（将其中的 `User` 和 `ExecStart` 中的 Python 路径替换为你的实际路径）：

```ini
[Unit]
Description=AirSync WebSocket Relay Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/airsync-relay
Environment="RELAY_HOST=127.0.0.1"
Environment="RELAY_PORT=8899"
ExecStart=/home/ubuntu/miniforge3/envs/airsync-relay/bin/python /opt/airsync-relay/server.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

启用并检查服务状态：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now airsync-relay
sudo systemctl status airsync-relay
# 实时查看日志：
sudo journalctl -u airsync-relay -f
```

### 第 3 步：配置 TLS 证书与 Nginx 反向代理（二选一）

中转服务默认只监听 `127.0.0.1:8899`（明文 `ws://`），公网必须通过 Nginx 终止 TLS 暴露为 `wss://`。

#### 方式 A：有域名 + Let's Encrypt 正规证书（推荐，客户端免填指纹）

```bash
sudo apt update && sudo apt install -y nginx certbot python3-certbot-nginx
sudo certbot --nginx -d relay.example.com
```

在对应站点的 Nginx 配置（如 `/etc/nginx/sites-available/airsync-relay`）中加入 WebSocket 转发规则：

```nginx
server {
    listen 443 ssl http2;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    location /relay {
        proxy_pass http://127.0.0.1:8899;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        client_max_body_size 10m;
    }
}
```

#### 方式 B：无域名、直接用服务器公网 IP + 自签证书（客户端配合 SHA-256 指纹固定）

1. 在服务器生成 10 年期自签证书（将 `<你的服务器IP>` 替换为实际公网 IP，**私钥仅保存在服务器，切勿上传仓库**）：

```bash
sudo mkdir -p /etc/nginx/ssl/airsync-relay
sudo openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
  -keyout /etc/nginx/ssl/airsync-relay/relay.key \
  -out /etc/nginx/ssl/airsync-relay/relay.crt \
  -subj "/CN=<你的服务器IP>" \
  -addext "subjectAltName=IP:<你的服务器IP>"
sudo chmod 600 /etc/nginx/ssl/airsync-relay/relay.key
```

2. 计算叶证书 SHA-256 指纹（稍后填进手机和 Mac 客户端）：

```bash
openssl x509 -in /etc/nginx/ssl/airsync-relay/relay.crt -noout -fingerprint -sha256 \
  | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f'
```

3. 配置 Nginx（`/etc/nginx/sites-available/airsync-relay`）：

```nginx
server {
    listen 443 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/ssl/airsync-relay/relay.crt;
    ssl_certificate_key /etc/nginx/ssl/airsync-relay/relay.key;

    location /relay {
        proxy_pass http://127.0.0.1:8899;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        client_max_body_size 10m;
    }
}
```

#### 重载 Nginx 并放行防火墙 443 端口

```bash
sudo ln -sf /etc/nginx/sites-available/airsync-relay /etc/nginx/sites-enabled/airsync-relay
sudo nginx -t && sudo systemctl reload nginx
# 如启用 ufw / 云安全组，请确保放行 TCP 443（8899 无需对外放行）
```

---

## 三、客户端配置方法

在 **Android 端（设置 → 传输模式）** 和 **Mac 端（设置 → 连接）** 同时选择「服务器中转」，填入一致的配置：

- **服务器地址**：
  - 方式 A：`wss://relay.example.com/relay`
  - 方式 B：`wss://<你的服务器IP>/relay`
- **房间名（Room）**：任意自定义名称，例如 `my-airsync`
- **密钥（Secret）**：至少 16 位强随机字符串（可用 `openssl rand -hex 16` 生成），两端必须完全一致
- **证书 SHA-256 指纹**：
  - 方式 A（正规 CA 证书）：**留空**
  - 方式 B（自签证书）：粘贴第 3 步生成的 64 位十六进制指纹（支持带冒号或大小写，客户端会自动规范化）

---

## 四、本地回归测试与联调验证

所有 Python 命令均建议使用 Conda 环境运行：

```bash
# 1. 运行内置 10 项协议与并发回归测试（无需外网）
conda run -n airsync-relay python test_regressions.py -v

# 2. 启动本地中转服务后，运行双端模拟互通测试
conda run -n airsync-relay python server.py
# 另开终端执行：
conda run -n airsync-relay python test_relay.py ws://127.0.0.1:8899
```
