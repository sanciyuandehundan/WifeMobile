"""The phone's side of protocol.md in Python, for testing the computer's side (the real phone is android/)."""
from __future__ import annotations

import asyncio
import os
from urllib.parse import parse_qs, unquote, urlparse

from mobile.call import Call, call_keys
from mobile.crypto import b64, hk, link_key, open_sealed, seal, stamp, unb64, x25519_pair


class Phone(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.inbox: asyncio.Queue = asyncio.Queue()
        self.key = self.dev = None
        self.call: Call | None = None
        self.transport = None

    async def open(self) -> "Phone":
        loop = asyncio.get_running_loop()
        self.transport, _ = await loop.create_datagram_endpoint(lambda: self, local_addr=("127.0.0.1", 0))
        return self

    def datagram_received(self, data: bytes, address) -> None:
        if data[:2] == b"WC" and self.call:
            self.call.received(data, address)
        else:
            self.inbox.put_nowait((data, address))

    def send(self, data: bytes, address) -> None:
        self.transport.sendto(data, address)

    async def reply(self, timeout: float = 2.0) -> tuple[bytes, tuple]:
        return await asyncio.wait_for(self.inbox.get(), timeout)

    async def pair(self, qr: str, to, name: str = "测试手机") -> dict:
        query = {k: v[0] for k, v in parse_qs(urlparse(qr).query).items()}
        pc_pub, secret = unb64(query["pc"]), unb64(query["s"])
        assert unquote(query["n"])
        private, public = x25519_pair()
        head = b"WP1" + public
        self.pair_request = head + seal(hk(secret, b"pair"), stamp({"name": name}), head)
        self.send(self.pair_request, to)
        data, _ = await self.reply()
        self.pair_reply = data
        key = link_key(private, public, pc_pub, secret, pc_side=False)
        paired = open_sealed(key, data[35:], head)
        self.key, self.dev = key, unb64(paired["dev"])
        return paired

    def sealed(self, message: dict) -> bytes:
        head = b"WM1" + self.dev
        return head + seal(self.key, stamp(message), head)

    def open_reply(self, data: bytes) -> dict:
        return open_sealed(self.key, data[11:], data[:11])

    async def dial(self, to, *, near: bool = True) -> dict:
        sid, (private, e_phone) = os.urandom(4), x25519_pair()
        offer = self.sealed({"type": "offer", "sid": b64(sid), "e": b64(e_phone), "near": near})
        self.send(offer, to)
        data, address = await self.reply()
        answer = self.open_reply(data)
        to_pc, to_phone = call_keys(private, unb64(answer["e"]), self.key, sid, e_phone, unb64(answer["e"]))
        self.messages: asyncio.Queue = asyncio.Queue()
        self.call = Call(self.send, sid, to_pc, to_phone, address, on_message=self.messages.put_nowait)
        self.offer = offer
        return answer
