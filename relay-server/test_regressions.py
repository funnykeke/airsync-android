"""Run with a conda Python: python -m unittest discover -p 'test_regressions.py'."""

import asyncio
import json
import logging
import time
import unittest
import uuid

import websockets
from websockets.exceptions import ConnectionClosed

import server


class DelayedCloseSocket:
    """Queue-backed transport; close can wait while already queued frames arrive."""

    EOF = object()

    def __init__(self, room, role, delay_close=False):
        self.incoming = asyncio.Queue()
        self.sent = asyncio.Queue()
        self.closing = asyncio.Queue()
        self.allow_close = asyncio.Event()
        if not delay_close:
            self.allow_close.set()
        self.incoming.put_nowait(json.dumps({"op": "join", "room": room,
                                            "secret": "regression-test-secret-only", "role": role}))

    async def recv(self):
        return await self.incoming.get()

    def __aiter__(self):
        return self

    async def __anext__(self):
        message = await self.incoming.get()
        if message is self.EOF:
            raise StopAsyncIteration
        return message

    async def send(self, message):
        self.sent.put_nowait(message)

    async def close(self, code=1000, reason=""):
        self.closing.put_nowait((code, reason))
        await self.allow_close.wait()


class RelayRegressionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        server.registry = server.RoomRegistry()
        self.listener = await websockets.serve(server.handler, "127.0.0.1", 0)
        self.url = f"ws://127.0.0.1:{self.listener.sockets[0].getsockname()[1]}"
        self.room = uuid.uuid4().hex
        self.clients = []

    async def asyncTearDown(self):
        for client in self.clients:
            await client.close()
        self.listener.close()
        await self.listener.wait_closed()

    async def join(self, role, secret="regression-test-secret-only"):
        client = await websockets.connect(self.url)
        self.clients.append(client)
        await client.send(json.dumps({"op": "join", "room": self.room,
                                      "secret": secret, "role": role}))
        ack = json.loads(await asyncio.wait_for(client.recv(), 2))
        self.assertEqual(ack["op"], "joined")
        return client, ack

    async def test_replacement_does_not_mark_live_peer_offline(self):
        mac, _ = await self.join("mac")
        old, _ = await self.join("android")
        await mac.recv()
        replacement, _ = await self.join("android")
        await asyncio.wait_for(old.wait_closed(), 2)
        await replacement.send("new-peer-is-live")
        states = []
        while True:
            message = await asyncio.wait_for(mac.recv(), 2)
            if message == "new-peer-is-live":
                break
            states.append(json.loads(message).get("state"))
        try:
            message = await asyncio.wait_for(mac.recv(), 0.15)
            states.append(json.loads(message).get("state"))
        except asyncio.TimeoutError:
            pass
        self.assertIn("online", states)
        self.assertNotIn("offline", states)

    async def test_actual_peer_close_reports_offline(self):
        mac, _ = await self.join("mac")
        phone, _ = await self.join("android")
        await mac.recv()
        await phone.close()
        event = json.loads(await asyncio.wait_for(mac.recv(), 2))
        self.assertEqual(event, {"op": "peer", "role": "android", "state": "offline"})

    async def test_superseded_socket_cannot_send_during_delayed_close(self):
        for message in ("stale-ciphertext", b"\x00stale-binary\xff", '{"op":"ping"}'):
            with self.subTest(message=message):
                room = uuid.uuid4().hex
                mac = DelayedCloseSocket(room, "mac")
                old = DelayedCloseSocket(room, "android", delay_close=True)
                current = DelayedCloseSocket(room, "android")
                tasks = []
                try:
                    for socket in (mac, old, current):
                        tasks.append(asyncio.create_task(server.handler(socket)))
                        ack = json.loads(await asyncio.wait_for(socket.sent.get(), 2))
                        self.assertEqual(ack["op"], "joined")
                        if socket is not mac:
                            event = json.loads(await asyncio.wait_for(mac.sent.get(), 2))
                            self.assertEqual(event["state"], "online")
                    await asyncio.wait_for(old.closing.get(), 2)  # takeover began closing
                    self.assertFalse(old.allow_close.is_set())
                    old.incoming.put_nowait(message)
                    old.incoming.put_nowait(old.EOF)
                    # Second close is handler's finally: the queued frame was processed.
                    await asyncio.wait_for(old.closing.get(), 2)
                    self.assertTrue(mac.sent.empty(), "Superseded socket forwarded a frame")
                    self.assertTrue(old.sent.empty(), "Superseded socket received a pong")
                    current.incoming.put_nowait(message)
                    target = current if message == '{"op":"ping"}' else mac
                    delivered = await asyncio.wait_for(target.sent.get(), 2)
                    if target is current:
                        self.assertEqual(json.loads(delivered), {"op": "pong"})
                    else:
                        self.assertEqual(delivered, message)
                finally:
                    for socket in (mac, old, current):
                        socket.allow_close.set()
                        socket.incoming.put_nowait(socket.EOF)
                    await asyncio.wait_for(asyncio.gather(*tasks), 2)

    async def test_offline_send_is_explicit_error_and_room_stays_open(self):
        mac, _ = await self.join("mac")
        await mac.send("opaque-ciphertext")
        error = json.loads(await asyncio.wait_for(mac.recv(), 2))
        self.assertEqual(error, {"op": "error", "reason": "peer offline"})
        await mac.send('{"op":"ping"}')
        self.assertEqual(json.loads(await asyncio.wait_for(mac.recv(), 2)), {"op": "pong"})

    async def test_non_object_handshake_is_rejected_as_bad_request(self):
        for payload in ([], None, 12, "hello"):
            with self.subTest(payload=payload):
                client = await websockets.connect(self.url)
                self.clients.append(client)
                await client.send(json.dumps(payload))
                with self.assertRaises(ConnectionClosed) as closed:
                    await asyncio.wait_for(client.recv(), 2)
                self.assertEqual(closed.exception.rcvd.code, 4400)

    async def test_non_string_room_is_rejected(self):
        client = await websockets.connect(self.url)
        self.clients.append(client)
        await client.send(json.dumps({"op": "join", "room": ["unexpected"],
                                      "secret": "regression-test-secret-only", "role": "mac"}))
        try:
            await asyncio.wait_for(client.recv(), 2)
        except ConnectionClosed as closed:
            self.assertEqual(closed.rcvd.code, 4400)
        else:
            self.fail("Non-string room was accepted")

    async def test_malformed_unicode_and_excessive_integer_are_bad_requests(self):
        valid = {"op": "join", "room": self.room,
                 "secret": "regression-test-secret-only", "role": "mac"}
        cases = {"excessive_integer": '{"op":"join","room":' + "1" * 5000 + '}',
                 **{f"surrogate_{key}": json.dumps({**valid, key: value + "\ud800"})
                    for key, value in valid.items() if key in ("room", "secret", "role")}}
        for name, payload in cases.items():
            with self.subTest(case=name):
                client = await websockets.connect(self.url)
                self.clients.append(client)
                await client.send(payload)
                with self.assertRaises(ConnectionClosed) as closed:
                    await asyncio.wait_for(client.recv(), 2)
                self.assertEqual(closed.exception.rcvd.code, 4400)

    async def test_business_json_with_op_is_forwarded_unchanged(self):
        mac, _ = await self.join("mac")
        phone, _ = await self.join("android")
        await mac.recv()
        message = '{"type":"notification","op":"ping","data":{"text":"中文"}}'
        await phone.send(message)
        self.assertEqual(await asyncio.wait_for(mac.recv(), 2), message)

    async def test_empty_room_ttl_starts_at_last_disconnect(self):
        registry = server.RoomRegistry()
        room = await registry.get_or_create("old-room")
        room.created_at = time.time() - server.ROOM_TTL - 1
        room.peers["mac"] = server.Peer(role="mac", last_seen=time.time())
        await registry.cleanup()
        self.assertIs(await registry.get_or_create("old-room"), room)

    async def test_empty_room_is_collected_after_last_disconnect_ttl(self):
        registry = server.RoomRegistry()
        room = await registry.get_or_create("expired-room")
        expired = time.time() - server.ROOM_TTL - 1
        room.created_at = expired - 100
        room.peers["mac"] = server.Peer(role="mac", last_seen=expired)
        room.peers["android"] = server.Peer(role="android", last_seen=expired - 50)
        await registry.cleanup()
        self.assertIsNot(await registry.get_or_create("expired-room"), room)


if __name__ == "__main__":
    logging.disable(logging.CRITICAL)
    unittest.main()
