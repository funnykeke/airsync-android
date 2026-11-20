#!/usr/bin/env python3
"""
AirSync Relay Server —— 盲转发中转服务

设计原则：
1. 盲转发：服务器只转发字节，永不解析、永不存储消息内容。
   端到端 AES-256-GCM 由两端自己处理，服务器看到的是密文。
2. 房间配对：手机和 Mac 用同一个 room + secret 加入同一房间，互为对端。
3. 两种角色对等：每个房间仅有一个 Mac 和一个 Android，支持新连接接管旧连接。
4. 断线重连：房间在最后一端离线后保留一段时间。

协议（纯文本控制帧，不进入业务数据流）：
  客户端 -> 服务器:
    {"op":"join","room":"...","secret":"...","role":"mac"|"android"}
    {"op":"ping"}
  服务器 -> 客户端:
    {"op":"joined","peer":{"role":...,"online":bool}}
    {"op":"peer","state":"online"|"offline"}
    {"op":"pong"}
  业务帧：不做任何解析，原样转发给对端。
"""

import asyncio
import hashlib
import json
import logging
import os
import signal
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Optional

import websockets
from websockets.exceptions import ConnectionClosed
from websockets.asyncio.server import ServerConnection

# ---------------------------------------------------------------- 配置

LISTEN_HOST = os.environ.get("RELAY_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("RELAY_PORT", "8899"))

# 房间在无人连接后保留多久（秒），方便短暂断网重连
ROOM_TTL = int(os.environ.get("RELAY_ROOM_TTL", "600"))
# 握手超时：连上后多久内必须 join
JOIN_TIMEOUT = int(os.environ.get("RELAY_JOIN_TIMEOUT", "15"))
# 单条消息上限（字节），防止被当图床。
# 注意：AirSync 的 appIcons 等消息本身已经过一层 base64，再经 AES+base64 后
# 线上帧相对原始二进制会膨胀约 1.78 倍。8MB 上限可容纳约 4.4MB 原始二进制。
MAX_MESSAGE = int(os.environ.get("RELAY_MAX_MESSAGE", str(8 * 1024 * 1024)))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("relay")


# ---------------------------------------------------------------- 数据模型


@dataclass
class Peer:
    """房间内的一侧（mac 或 android）。"""

    role: str
    ws: Optional[ServerConnection] = None
    last_seen: float = field(default_factory=time.time)

    @property
    def online(self) -> bool:
        return self.ws is not None


@dataclass
class Room:
    name: str
    created_at: float = field(default_factory=time.time)
    peers: dict = field(default_factory=dict)  # role -> Peer

    def other(self, role: str) -> Optional[Peer]:
        for r, p in self.peers.items():
            if r != role:
                return p
        return None


class RoomRegistry:
    def __init__(self) -> None:
        self._rooms: "OrderedDict[str, Room]" = OrderedDict()
        self._lock = asyncio.Lock()

    async def get_or_create(self, name: str) -> Room:
        async with self._lock:
            room = self._rooms.get(name)
            if room is None:
                room = Room(name=name)
                self._rooms[name] = room
                log.info("room created: %s (total=%d)", name, len(self._rooms))
            return room

    async def cleanup(self) -> None:
        """清理长期无人连接的空房间。"""
        now = time.time()
        async with self._lock:
            dead = [
                name
                for name, room in self._rooms.items()
                if not any(p.online for p in room.peers.values())
                and now - max([room.created_at] + [p.last_seen for p in room.peers.values()]) > ROOM_TTL
            ]
            for name in dead:
                self._rooms.pop(name, None)
                log.info("room expired: %s (total=%d)", name, len(self._rooms))


registry = RoomRegistry()


# ---------------------------------------------------------------- 鉴权


def fingerprint(secret: str) -> str:
    """把 secret 变成房间名的一部分，避免明文 secret 出现在日志里。

    这样即使日志泄露，也拿不到原始密钥；同时不同 secret 天然隔离到不同房间。
    """
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()[:32]


def _frame_op(message: str) -> Optional[str]:
    """若该文本帧是控制帧，返回其 op；否则返回 None。

    判定条件：整个帧是一个 JSON 对象，且只含 op/data 两个键。
    业务帧是加密后的 base64，不以 '{' 开头，因此不会被误判。
    这样即使业务 JSON 里恰好有 "op" 字段，也不会被吞掉。
    """
    if not message.startswith("{"):
        return None
    try:
        obj = json.loads(message)
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(obj, dict):
        return None
    keys = set(obj.keys())
    if not keys.issubset({"op", "data", "room"}):
        return None
    op = obj.get("op")
    return op if isinstance(op, str) else None


def _is_control_frame(message: str) -> bool:
    return _frame_op(message) is not None


# ---------------------------------------------------------------- 连接处理


def derive_room(room: str, secret: str) -> str:
    return f"{room}:{fingerprint(secret)}"


async def send_json(ws: ServerConnection, payload: dict) -> None:
    try:
        await ws.send(json.dumps(payload, separators=(",", ":")))
    except (ConnectionClosed, RuntimeError):
        pass


async def handler(ws: ServerConnection) -> None:
    remote = getattr(ws, "remote_address", None)
    peer_addr = f"{remote[0]}:{remote[1]}" if remote else "unknown"
    room: Optional[Room] = None
    role: Optional[str] = None

    try:
        raw = await asyncio.wait_for(ws.recv(), timeout=JOIN_TIMEOUT)
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8", "replace")

        try:
            hello = json.loads(raw)
        except (json.JSONDecodeError, ValueError):
            await ws.close(code=4400, reason="bad json")
            return

        if not isinstance(hello, dict) or hello.get("op") != "join":
            await ws.close(code=4400, reason="expected join")
            return

        room_key = hello.get("room")
        secret = hello.get("secret")
        proposed_role = hello.get("role")

        if not all(isinstance(value, str) for value in (room_key, secret, proposed_role)):
            await ws.close(code=4400, reason="bad join params")
            return
        room_key = room_key.strip()
        role = proposed_role.strip().lower()

        if not room_key or len(room_key) > 128 or not 16 <= len(secret) <= 1024 or role not in ("mac", "android"):
            await ws.close(code=4400, reason="bad join params")
            return
        try:
            room_key.encode("utf-8")
            secret.encode("utf-8")
        except UnicodeEncodeError:
            await ws.close(code=4400, reason="bad join params")
            return

        # 只允许这两种角色的组合：mac + android
        room_name = derive_room(room_key, secret)
        room = await registry.get_or_create(room_name)

        async with registry._lock:  # 简单的互斥，避免同角色抢占
            existing = room.peers.get(role)
            if existing and existing.online:
                # 同角色已在线上：踢掉旧的，让新连接接管（应对手机重连）
                log.info("role %s reconnected in room %s, dropping old", role, room_key)
                old = existing.ws
                if old is not None:
                    asyncio.create_task(_safe_close(old))

            peer = room.peers.get(role) or Peer(role=role)
            peer.ws = ws
            peer.last_seen = time.time()
            room.peers[role] = peer

            other = room.other(role)

        log.info("joined room=%s role=%s peer_online=%s from=%s",
                 room_key, role, other.online if other else False, peer_addr)

        await send_json(ws, {
            "op": "joined",
            "role": role,
            "peer": {"role": other.role if other else None,
                     "online": bool(other and other.online)},
        })

        # 通知对端：我上线了
        if other and other.online:
            await send_json(other.ws, {"op": "peer", "role": role, "state": "online"})

        # ---- 主循环：盲转发 ----
        # 控制帧与业务帧的区分原则：控制帧由客户端显式用 join/ping 发起，
        # 业务帧一律是加密后的 base64 文本（不会以 '{' 开头）。
        # 因此只用「单个 op 键的纯 JSON」判定控制帧，避免误吞业务消息。
        async for message in ws:
            # 接管后旧连接可能仍在完成关闭握手，不再允许它转发业务或心跳。
            async with registry._lock:
                current = room.peers.get(role)
                if current is None or current.ws is not ws:
                    break
            if isinstance(message, str) and _is_control_frame(message):
                if _frame_op(message) == "ping":
                    await send_json(ws, {"op": "pong"})
                    continue
            other = room.other(role)
            if other and other.online:
                try:
                    await other.ws.send(message)
                except (ConnectionClosed, RuntimeError):
                    # 转发失败：告知发送方，避免"以为发出去了"的静默丢失
                    await send_json(ws, {"op": "error", "reason": "peer send failed"})
            else:
                # 对端不在线：不缓冲（避免内存增长和隐私留存），
                # 但必须明确告知发送方，否则消息静默丢失且无人感知
                await send_json(ws, {"op": "error", "reason": "peer offline"})

    except asyncio.TimeoutError:
        await _safe_close(ws, 4408, "join timeout")
    except ConnectionClosed:
        pass
    except Exception as exc:  # noqa: BLE001
        log.exception("handler error: %s", exc)
    finally:
        offline_target = None
        if room is not None and role is not None:
            async with registry._lock:
                peer = room.peers.get(role)
                if peer and peer.ws is ws:
                    peer.ws = None
                    peer.last_seen = time.time()
                    other = room.other(role)
                    if other and other.online:
                        offline_target = other.ws
            # 网络 I/O 不持有全局 registry 锁，防止一个慢客户端阻塞全部房间。
            if offline_target is not None:
                await send_json(offline_target, {"op": "peer", "role": role, "state": "offline"})
            log.info("left room role=%s", role)
        await _safe_close(ws)


async def _safe_close(ws: ServerConnection, code: int = 1000, reason: str = "") -> None:
    try:
        await ws.close(code=code, reason=reason)
    except Exception:  # noqa: BLE001
        pass


# ---------------------------------------------------------------- 生命周期


async def reaper() -> None:
    while True:
        await asyncio.sleep(60)
        try:
            await registry.cleanup()
        except Exception as exc:  # noqa: BLE001
            log.warning("cleanup failed: %s", exc)


async def main() -> None:
    stop = asyncio.Event()

    def _signal(*_a):  # noqa: ANN001
        log.info("shutting down")
        stop.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(sig, _signal)
        except NotImplementedError:
            pass

    async with websockets.serve(
        handler,
        LISTEN_HOST,
        LISTEN_PORT,
        max_size=MAX_MESSAGE,
        ping_interval=30,
        ping_timeout=60,
        close_timeout=5,
    ):
        log.info("relay listening on ws://%s:%d", LISTEN_HOST, LISTEN_PORT)
        task = asyncio.create_task(reaper())
        await stop.wait()
        task.cancel()


if __name__ == "__main__":
    asyncio.run(main())
