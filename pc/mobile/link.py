"""The computer's UDP port 18766 (protocol.md): pairing, handshakes, sessions, and the DHT when it is chosen."""
from __future__ import annotations

import asyncio
import logging
import os
import socket
import time
from dataclasses import dataclass, field
from urllib.parse import quote

from cryptography.exceptions import InvalidTag

from .crypto import Seen, b64, day, dht_key, hk, link_key, open_sealed, seal, stamp, unb64, x25519_pair
from .dht import BOOTSTRAP, Node
from .net import PORT, addr, global_ipv6, lan_ipv4
from .session import Session, session_keys
from .store import Device, Store

logger = logging.getLogger("mobile.link")
PAIRING_SECONDS = 300


@dataclass
class Pairing:
    secret: bytes
    private: object
    public: bytes
    until: float
    answered: dict = field(default_factory=dict)       # phone key -> our reply, in case the reply got lost


class _Port(asyncio.DatagramProtocol):
    def __init__(self, link: "Link") -> None:
        self.link = link

    def datagram_received(self, data: bytes, address) -> None:
        self.link.received(data, address[:2])

    def error_received(self, exc: Exception) -> None:      # Windows reports ICMP "port unreachable" here
        pass


class Link:
    def __init__(self, store: Store, *, port: int = PORT, on_session=lambda session, device: None,
                 on_ended=lambda session, device: None, on_paired=lambda device: None, on_show=lambda: None,
                 local: bool = False, bootstrap=BOOTSTRAP) -> None:
        self.store, self.port = store, port
        self.on_session, self.on_ended, self.on_paired, self.on_show = on_session, on_ended, on_paired, on_show
        self.local, self.bootstrap = local, bootstrap
        self.sessions: dict[bytes, tuple[Session, Device]] = {}
        self.pairing: Pairing | None = None
        self.dht: Node | None = None
        self._seen = Seen()
        self._v4 = self._v6 = None
        self._last_here: dict | None = None
        self._published = 0.0
        self._tasks: list[asyncio.Task] = []

    @property
    def name(self) -> str:
        return self.store.settings.get("name") or socket.gethostname()

    async def start(self) -> None:
        """Raises OSError when the port is taken (another copy of this program runs)."""
        loop = asyncio.get_running_loop()
        self._v4, _ = await loop.create_datagram_endpoint(lambda: _Port(self), local_addr=("0.0.0.0", self.port),
                                                          allow_broadcast=True)
        self.port = self._v4.get_extra_info("sockname")[1]       # tests ask for port 0
        try:
            sock = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
            sock.bind(("::", self.port))
            self._v6, _ = await loop.create_datagram_endpoint(lambda: _Port(self), sock=sock)
        except OSError:
            logger.info("这台电脑没有 IPv6")
        self._tasks.append(asyncio.create_task(self._watch()))

    def stop(self) -> None:
        for session, _device in list(self.sessions.values()):
            session.hang_up()
        for task in self._tasks:
            task.cancel()
        if self.dht:
            self.dht.save()
        for port in (self._v4, self._v6):
            if port:
                port.close()

    def send(self, data: bytes, address) -> None:
        port = self._v6 if ":" in address[0] else self._v4
        if port:
            port.sendto(data, address)

    def received(self, data: bytes, address) -> None:
        if data[:1] == b"d":
            if self.dht:
                self.dht.received(data, address)
        elif data[:3] == b"WP1":
            self._pair(data, address)
        elif data[:3] == b"WM1":
            self._handshake(data, address)
        elif data[:2] == b"WC":
            entry = self.sessions.get(data[2:6])
            if entry:
                entry[0].received(data, address)
        elif data == b"WSHOW" and address[0] == "127.0.0.1":     # a second copy asked us to show the window
            self.on_show()

    # ── where this computer is ──
    def here(self, *, nodes: bool = True) -> dict:
        away = [addr(ip, self.port) for ip in global_ipv6()]
        if self.store.find == "dht" and self.dht and self.dht.external():
            away.append(addr(*self.dht.external()))
        here = {"type": "here", "name": self.name, "find": self.store.find,
                "lan": [addr(ip, self.port) for ip in lan_ipv4()], "away": away}
        if nodes and self.store.find == "dht" and self.dht:
            here["nodes"] = self.dht.good()
        return here

    async def _watch(self) -> None:
        """Every 30 s: tell the phones connected when our address changed; keep the DHT entry fresh."""
        while True:
            if self.store.find == "dht" and self.dht is None:
                self.dht = Node(self.send, cache=self.store.dht_file, local=self.local, bootstrap=self.bootstrap)
                self._tasks.append(asyncio.create_task(self.dht.maintain()))
                await self.dht.join()
            elif self.store.find != "dht" and self.dht is not None:
                self.dht.save()
                self.dht = None
            here = self.here(nodes=False)
            changed = here != self._last_here
            self._last_here = here
            if changed:
                for session, _device in self.sessions.values():
                    session.message(self.here())
            if self.dht and (changed or time.time() - self._published > 3600):
                await self.publish()
            await asyncio.sleep(30)

    async def publish(self) -> None:
        here = self.here(nodes=False)
        for device in list(self.store.devices.values()):
            took = await self.dht.put(dht_key(device.key, day()), b"pc", seal(device.key, stamp(here), b"pc"))
            logger.info("在 BT 网络里更新了电脑的地址（%s，%d 个节点收下）", device.name, took)
        self._published = time.time()

    # ── pairing ──
    def begin_pairing(self) -> str:
        """A new one-time code. The old one, if any, stops working; this one is never stored or shown again."""
        private, public = x25519_pair()
        self.pairing = Pairing(os.urandom(32), private, public, time.monotonic() + PAIRING_SECONDS)
        lan = ",".join(addr(ip, self.port) for ip in lan_ipv4())
        return f"wife://pair?v=1&pc={b64(public)}&s={b64(self.pairing.secret)}&n={quote(self.name)}&l={lan}"

    def end_pairing(self) -> None:
        self.pairing = None

    def _pair(self, data: bytes, address) -> None:
        pairing = self.pairing
        if pairing is None or time.monotonic() > pairing.until or len(data) < 35 + 28:
            return
        head, phone = data[:35], data[3:35]
        if phone in pairing.answered:
            return self.send(pairing.answered[phone], address)
        if pairing.secret is None:
            return
        try:
            request = open_sealed(hk(pairing.secret, b"pair"), data[35:], head)
        except InvalidTag:
            return
        if not self._seen.fresh(request):
            return
        key = link_key(pairing.private, pairing.public, phone, pairing.secret, pc_side=True)
        pairing.secret = pairing.private = None          # burned: this code can't pair anything else
        pairing.until = time.monotonic() + 60
        device = self.store.add(os.urandom(8), str(request.get("name") or "手机")[:40], key)
        reply = head + seal(key, stamp({**self.here(), "type": "paired", "dev": b64(device.dev)}), head)
        pairing.answered[phone] = reply
        self.send(reply, address)
        logger.info("配对了：%s", device.name)
        self.on_paired(device)

    # ── handshakes ──
    def _handshake(self, data: bytes, address) -> None:
        device = self.store.devices.get(data[3:11])
        if device is None:
            return
        head = data[:11]
        try:
            message = open_sealed(device.key, data[11:], head)
        except InvalidTag:
            return
        kind = message.get("type")
        if kind == "offer":
            entry = self.sessions.get(unb64(message.get("sid", "")))
            if entry and entry[1] is device and getattr(entry[0], "answer_id", None) == message.get("id"):
                return self.send(entry[0].answer, address)          # the phone didn't get our answer
        if not self._seen.fresh(message):
            return
        if kind == "where":
            self.send(head + seal(device.key, stamp(self.here()), head), address)
        elif kind == "offer":
            self._answer(device, message, head, address)

    def _ended(self, sid: bytes, session: Session, device: Device) -> None:
        self.sessions.pop(sid, None)
        got = "、".join(f"{k} {v}" for k, v in session.got.items())
        logger.info("%s 断开了：收到 %s，最后一个包在 %.0f 秒前", device.name, got, time.monotonic() - session.heard)
        self.on_ended(session, device)

    def _answer(self, device: Device, offer: dict, head: bytes, address) -> None:
        for sid, (old, owner) in list(self.sessions.items()):
            if owner is device:                       # it dialled again: the old session is gone
                old.close()
        sid, e_phone = unb64(offer["sid"]), unb64(offer["e"])
        private, e_pc = x25519_pair()
        to_pc, to_phone = session_keys(private, e_phone, device.key, sid, e_phone, e_pc)
        session = Session(self.send, sid, to_phone, to_pc, address, ping=float(offer.get("ping") or 5))
        session.answer_id = offer["id"]
        session.answer = head + seal(device.key, stamp({"type": "answer", "sid": offer["sid"], "re": offer["id"],
                                                        "e": b64(e_pc)}), head)
        self.sessions[sid] = (session, device)
        session.on_closed = lambda: self._ended(sid, session, device)
        self.send(session.answer, address)
        logger.info("%s 连上了（%s）", device.name, addr(*address[:2]))
        self.on_session(session, device)
        self._tasks = [t for t in self._tasks if not t.done()] + [asyncio.create_task(session.run())]
