#!/usr/bin/env python3
"""中转服务器的双端互通测试。

模拟：Mac 端先接入（role=mac），手机端后接入（role=android），
验证双向转发、角色在线通知、同角色重连接管、错误密钥隔离。
"""

import asyncio
import json
import sys

import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8899"
ROOM = "test-room"
SECRET = "0123456789abcdef0123456789abcdef"  # 32 字符

PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(("  PASS " if ok else "  FAIL ") + name + (f"  [{detail}]" if detail else ""))


async def join(role, room=ROOM, secret=SECRET):
    ws = await websockets.connect(URL, open_timeout=5)
    await ws.send(json.dumps({"op": "join", "room": room, "secret": secret, "role": role}))
    ack = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    return ws, ack


async def main():
    print(f"== 测试目标 {URL}\n")

    print("[1] 双端接入")
    mac, mac_ack = await join("mac")
    check("Mac join 成功", mac_ack.get("op") == "joined", mac_ack.get("op"))
    check("Mac 收到对端离线", mac_ack.get("peer", {}).get("online") is False)

    phone, phone_ack = await join("android")
    check("手机 join 成功", phone_ack.get("op") == "joined")
    check("手机看到 Mac 在线", phone_ack.get("peer", {}).get("online") is True)

    peer_msg = json.loads(await asyncio.wait_for(mac.recv(), timeout=5))
    check("Mac 收到对端上线通知", peer_msg.get("op") == "peer" and peer_msg.get("state") == "online")

    print("\n[2] 手机 -> Mac 盲转发")
    payload = {"type": "notification", "data": {"title": "微信", "text": "你好"}}
    await phone.send(json.dumps(payload))
    got = json.loads(await asyncio.wait_for(mac.recv(), timeout=5))
    check("内容原样到达", got == payload)

    print("\n[3] Mac -> 手机 盲转发")
    payload2 = {"type": "notificationAction", "data": {"reply": "收到"}}
    await mac.send(json.dumps(payload2))
    got2 = json.loads(await asyncio.wait_for(phone.recv(), timeout=5))
    check("回程内容原样到达", got2 == payload2)

    print("\n[4] 二进制帧转发")
    await phone.send(b"\x00\x01\x02binary\xff")
    got3 = await asyncio.wait_for(mac.recv(), timeout=5)
    check("二进制帧透传", got3 == b"\x00\x01\x02binary\xff")

    print("\n[5] ping/pong")
    await phone.send('{"op":"ping"}')
    pong = json.loads(await asyncio.wait_for(phone.recv(), timeout=5))
    check("返回 pong", pong.get("op") == "pong")

    print("\n[6] 手机断开后 Mac 收到离线通知")
    await phone.close()
    off = json.loads(await asyncio.wait_for(mac.recv(), timeout=5))
    check("Mac 收到 offline", off.get("op") == "peer" and off.get("state") == "offline")

    print("\n[7] 手机重连可续上")
    phone2, ack2 = await join("android")
    check("重连成功", ack2.get("op") == "joined")
    check("重连后 Mac 仍在线", ack2.get("peer", {}).get("online") is True)
    await mac.recv()  # 消费 Mac 那边再次收到的 online
    await phone2.send(json.dumps({"type": "notify2"}))
    got4 = json.loads(await asyncio.wait_for(mac.recv(), timeout=5))
    check("重连后消息仍可达", got4 == {"type": "notify2"})
    await phone2.close()
    await asyncio.sleep(0.2)

    print("\n[8] 错误 secret 被隔离到另一房间")
    other, other_ack = await join("android", secret="ffffffffffffffffffffffffffffffff")
    check("不同 secret 看不到 Mac", other_ack.get("peer", {}).get("online") is False)
    await other.close()

    print("\n[9] 参数非法被拒绝")
    try:
        bad = await websockets.connect(URL, open_timeout=5)
        await bad.send(json.dumps({"op": "join", "room": ROOM, "secret": "short", "role": "mac"}))
        await asyncio.wait_for(bad.recv(), timeout=3)
        check("短 secret 被拒绝", False, "未断开")
    except Exception as exc:  # 期望被 close
        check("短 secret 被拒绝", True, type(exc).__name__)

    await mac.close()

    print(f"\n== 结果：{len(PASS)} 通过 / {len(FAIL)} 失败")
    if FAIL:
        print("失败项:", FAIL)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
