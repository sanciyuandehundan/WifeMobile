"""BitTorrent's DHT (BEP 5, 42, 43, 44) on Wife's UDP port, used only when the phone can't find the computer.

The computer is a full node while it runs: it answers other nodes' queries and keeps what they store, as every BT
client does. Its own use is small: one signed entry saying where it is, published when that changes and hourly.
"""
from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import json
import logging
import os
import socket
import time
from collections import Counter, deque
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from .bencode import decode, encode

logger = logging.getLogger("mobile.dht")

BOOTSTRAP = (("router.bittorrent.com", 6881), ("dht.transmissionbt.com", 6881), ("dht.libtorrent.org", 25401))
K, ALPHA, TIMEOUT = 8, 8, 2.0
ITEM_SECONDS, PEER_SECONDS = 2 * 3600, 30 * 60


def distance(a: bytes, b: bytes) -> int:
    return int.from_bytes(a, "big") ^ int.from_bytes(b, "big")


def compact(addr: tuple[str, int]) -> bytes:
    return socket.inet_aton(addr[0]) + addr[1].to_bytes(2, "big")


def uncompact(data: bytes) -> tuple[str, int]:
    return socket.inet_ntoa(data[:4]), int.from_bytes(data[4:6], "big")


def parse_nodes(data: bytes) -> list[tuple[bytes, tuple[str, int]]]:
    return [(data[i:i + 20], uncompact(data[i + 20:i + 26])) for i in range(0, len(data) - 25, 26)]


def _crc32c(data: bytes) -> int:
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82F63B78 if crc & 1 else 0)
    return crc ^ 0xFFFFFFFF


def secure_id(ip: str, rand: bytes | None = None) -> bytes:
    """BEP 42: the first 21 bits come from the node's public IP, so nodes can't pick where they sit."""
    rand = rand or os.urandom(20)
    r = rand[19] & 7
    crc = _crc32c(((int(ipaddress.ip_address(ip)) & 0x030F3FFF) | (r << 29)).to_bytes(4, "big"))
    return bytes([(crc >> 24) & 0xFF, (crc >> 16) & 0xFF, ((crc >> 8) & 0xF8) | (rand[2] & 7)]) + rand[3:20]


def is_secure(node_id: bytes, ip: str) -> bool:
    return secure_id(ip, node_id)[:3] == node_id[:3] or ipaddress.ip_address(ip).is_private


def signed(salt: bytes, seq: int, value: bytes) -> bytes:
    """What a BEP 44 mutable item's signature covers."""
    return (b"4:salt" + encode(salt) if salt else b"") + b"3:seq" + encode(seq) + b"1:v" + encode(value)


def public(key: Ed25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


class Node:
    def __init__(self, send, *, cache: Path | None = None, read_only: bool = False, local: bool = False,
                 bootstrap=BOOTSTRAP) -> None:
        self._send, self.cache, self.read_only, self.local = send, cache, read_only, local
        self.bootstrap = bootstrap
        self.id = os.urandom(20)
        self.table: dict[bytes, list] = {}         # node id -> [addr, last seen, failures]
        self.items: dict[bytes, dict] = {}         # what other nodes asked us to keep (BEP 44)
        self.peers: dict[bytes, dict] = {}         # info_hash -> {compact peer: time} (BEP 5)
        self.votes: deque = deque(maxlen=16)       # our address as other nodes see it (BEP 42 "ip")
        self._pending: dict[bytes, asyncio.Future] = {}
        self._secrets = [os.urandom(16), os.urandom(16)]
        self._rotated = time.monotonic()
        if cache and cache.is_file():
            saved = json.loads(cache.read_text(encoding="utf-8"))
            self.id = bytes.fromhex(saved["id"])
            for nid, host, port in saved["nodes"]:
                self.table[bytes.fromhex(nid)] = [(host, port), 0.0, 0]

    # ── addresses ──
    def usable(self, addr: tuple[str, int]) -> bool:
        if addr[1] == 0:
            return False
        try:
            return self.local or ipaddress.ip_address(addr[0]).is_global
        except ValueError:
            return False

    def external(self) -> tuple[str, int] | None:
        """Our public IPv4 address and port, when most recent replies agree on it."""
        if len(self.votes) < 3:
            return None
        best, count = Counter(self.votes).most_common(1)[0]
        return best if count * 2 > len(self.votes) else None

    # ── routing table ──
    def _bucket(self, nid: bytes) -> int:
        return 160 - distance(self.id, nid).bit_length()

    def _saw(self, nid: bytes, addr: tuple[str, int]) -> None:
        if len(nid) != 20 or nid == self.id or not self.usable(addr):
            return
        entry = self.table.get(nid)
        if entry:
            entry[0], entry[1], entry[2] = addr, time.time(), 0
            return
        bucket = self._bucket(nid)
        same = [n for n in self.table if self._bucket(n) == bucket]
        if len(same) >= K:
            worst = max(same, key=lambda n: (self.table[n][2], -self.table[n][1]))
            if self.table[worst][2] == 0 and time.time() - self.table[worst][1] < 900:
                return
            del self.table[worst]
        self.table[nid] = [addr, time.time(), 0]

    def closest(self, target: bytes, n: int = K) -> list[tuple[bytes, tuple[str, int]]]:
        ranked = sorted(self.table, key=lambda nid: distance(nid, target))[:n]
        return [(nid, self.table[nid][0]) for nid in ranked]

    def _nodes(self, target: bytes) -> bytes:
        return b"".join(nid + compact(addr) for nid, addr in self.closest(target) if ":" not in addr[0])

    def good(self, n: int = 32) -> list[str]:
        recent = sorted(self.table.items(), key=lambda item: -item[1][1])
        return [f"{a[0][0]}:{a[0][1]}" for _, a in recent if a[2] == 0 and a[1] > 0][:n]

    def save(self) -> None:
        if self.cache:
            nodes = [[nid.hex(), *entry[0]] for nid, entry in self.table.items() if entry[2] == 0][:300]
            self.cache.write_text(json.dumps({"id": self.id.hex(), "nodes": nodes}), encoding="utf-8")

    # ── wire ──
    def received(self, data: bytes, addr: tuple[str, int]) -> None:
        try:
            message = decode(data)
            kind = message[b"y"]
        except (ValueError, KeyError, TypeError, IndexError):
            return
        if kind in (b"r", b"e"):
            future = self._pending.pop(message.get(b"t", b""), None)
            if future is not None and not future.done():
                future.set_result(message)
            if kind == b"r" and isinstance(message.get(b"r"), dict):
                self._saw(message[b"r"].get(b"id", b""), addr)
                ip = message.get(b"ip", b"")
                if len(ip) == 6:
                    self.votes.append(uncompact(ip))
        elif kind == b"q" and not self.read_only:
            try:
                self._answer(message, addr)
            except (KeyError, TypeError, ValueError, AttributeError):
                self._reply(message, addr, error=(203, "protocol error"))

    def _reply(self, query: dict, addr, *, body: dict | None = None, error: tuple | None = None) -> None:
        message = {"t": query.get(b"t", b""), "y": "e" if error else "r", "ip": compact(addr)}
        if error:
            message["e"] = [error[0], error[1]]
        else:
            message["r"] = {"id": self.id, **(body or {})}
        self._send(encode(message), addr)

    async def query(self, addr: tuple[str, int], method: bytes, args: dict) -> dict | None:
        tid = os.urandom(2)
        while tid in self._pending:
            tid = os.urandom(2)
        future = asyncio.get_running_loop().create_future()
        self._pending[tid] = future
        message = {"t": tid, "y": "q", "q": method, "a": {"id": self.id, **args}}
        if self.read_only:
            message["ro"] = 1
        self._send(encode(message), addr)
        try:
            reply = await asyncio.wait_for(future, TIMEOUT)
        except asyncio.TimeoutError:
            for entry in self.table.values():
                if entry[0] == addr:
                    entry[2] += 1
            return None
        finally:
            self._pending.pop(tid, None)
        return reply.get(b"r") if reply.get(b"y") == b"r" and isinstance(reply.get(b"r"), dict) else None

    # ── answering other nodes ──
    def _token(self, addr, secret: bytes) -> bytes:
        return hashlib.sha1(secret + addr[0].encode()).digest()[:8]

    def _valid_token(self, token: bytes, addr) -> bool:
        return any(token == self._token(addr, s) for s in self._secrets)

    def _answer(self, query: dict, addr) -> None:
        method, args = query[b"q"], query.get(b"a", {})
        if not query.get(b"ro"):                        # BEP 43: read-only nodes (phones) stay out of tables
            self._saw(args.get(b"id", b""), addr)
        token = self._token(addr, self._secrets[0])
        if method == b"ping":
            return self._reply(query, addr)
        if method == b"find_node":
            return self._reply(query, addr, body={"nodes": self._nodes(args[b"target"])})
        if method == b"get_peers":
            body = {"token": token, "nodes": self._nodes(args[b"info_hash"])}
            peers = self.peers.get(args[b"info_hash"])
            if peers:
                body["values"] = list(peers)[:50]
            return self._reply(query, addr, body=body)
        if method == b"announce_peer":
            if not self._valid_token(args[b"token"], addr):
                return self._reply(query, addr, error=(203, "bad token"))
            port = addr[1] if args.get(b"implied_port") else int(args[b"port"])
            peers = self.peers.setdefault(args[b"info_hash"], {})
            peers[compact((addr[0], port))] = time.time()
            if len(peers) > 100:
                del peers[min(peers, key=peers.get)]
            return self._reply(query, addr)
        if method == b"get":
            body = {"token": token, "nodes": self._nodes(args[b"target"])}
            item = self.items.get(args[b"target"])
            if item and (b"seq" not in args or item.get("seq", 0) > args[b"seq"]):
                body.update({k: v for k, v in item.items() if k != "at"})   # BEP 44: v, k, sig, seq
            elif item:
                body["seq"] = item["seq"]
            return self._reply(query, addr, body=body)
        if method == b"put":
            return self._put(query, args, addr)
        self._reply(query, addr, error=(204, "method unknown"))

    def _put(self, query: dict, args: dict, addr) -> None:
        if not self._valid_token(args[b"token"], addr):
            return self._reply(query, addr, error=(203, "bad token"))
        value = args[b"v"]
        if len(encode(value)) > 1000:
            return self._reply(query, addr, error=(205, "message (v field) too big"))
        if b"k" not in args:                       # immutable item: its hash is its name
            self.items[hashlib.sha1(encode(value)).digest()] = {"v": value, "at": time.time()}
            return self._reply(query, addr)
        key, salt, seq, sig = args[b"k"], args.get(b"salt", b""), int(args[b"seq"]), args[b"sig"]
        if len(salt) > 64:
            return self._reply(query, addr, error=(207, "salt (salt field) too big"))
        try:
            Ed25519PublicKey.from_public_bytes(key).verify(sig, signed(salt, seq, value))
        except (InvalidSignature, ValueError):
            return self._reply(query, addr, error=(206, "invalid signature"))
        target = hashlib.sha1(key + salt).digest()
        old = self.items.get(target)
        if old and b"cas" in args and old.get("seq") != args[b"cas"]:
            return self._reply(query, addr, error=(301, "CAS mismatch"))
        if old and old.get("seq", 0) > seq:
            return self._reply(query, addr, error=(302, "sequence number less than current"))
        self.items[target] = {"v": value, "k": key, "sig": sig, "seq": seq, "at": time.time()}
        if len(self.items) > 2000:
            del self.items[min(self.items, key=lambda t: self.items[t]["at"])]
        self._reply(query, addr)

    # ── looking things up ──
    async def lookup(self, target: bytes, method: bytes, args: dict,
                     start: list[tuple[str, int]] = ()) -> list[tuple[bytes, tuple[str, int], dict]]:
        """Walk towards ``target``; returns the nodes that answered, closest first, with their replies."""
        known = {addr: nid for nid, addr in self.closest(target, 2 * K)}
        known.update({addr: os.urandom(20) for addr in start if addr not in known})
        asked, failed, answers, flying = set(), set(), {}, {}
        while True:
            ranked = sorted((a for a in known if a not in failed), key=lambda a: distance(known[a], target))[:K]
            if ranked and all(a in answers for a in ranked):
                break                                  # the closest nodes we know of have all answered
            for addr in [a for a in ranked if a not in asked][:ALPHA - len(flying)]:
                asked.add(addr)
                flying[asyncio.create_task(self.query(addr, method, args))] = addr
            if not flying:
                break
            done, _ = await asyncio.wait(flying, return_when=asyncio.FIRST_COMPLETED)   # a slow node holds no one up
            for task in done:
                addr, reply = flying.pop(task), task.result()
                if reply is None or len(reply.get(b"id", b"")) != 20:
                    failed.add(addr)
                    continue
                known[addr] = reply[b"id"]
                answers[addr] = reply
                for nid, found in parse_nodes(reply.get(b"nodes", b"")):
                    if found not in known and self.usable(found):
                        known[found] = nid
        for task in flying:
            task.cancel()
        return sorted(((r[b"id"], a, r) for a, r in answers.items()), key=lambda x: distance(x[0], target))

    async def join(self) -> bool:
        """Find our neighbours: from the nodes we remember, and only when none answers, from BT's entry nodes."""
        if self.table and await self.lookup(self.id, b"find_node", {b"target": self.id}):
            return True
        if self.read_only or not self.bootstrap:
            return False
        loop = asyncio.get_running_loop()
        entry = []
        for host, port in self.bootstrap:
            try:
                infos = await loop.getaddrinfo(host, port, family=socket.AF_INET, type=socket.SOCK_DGRAM)
                entry += [(info[4][0], port) for info in infos[:2]]
            except OSError:
                continue
        logger.info("第一次进 BT 网络，问引导节点")
        return bool(await self.lookup(self.id, b"find_node", {b"target": self.id}, start=entry))

    async def get(self, pub: bytes, salt: bytes) -> tuple[int, bytes] | None:
        """The newest correctly signed value of a mutable item, or None."""
        target = hashlib.sha1(pub + salt).digest()
        best = None
        for _nid, _addr, reply in await self.lookup(target, b"get", {b"target": target}):
            if reply.get(b"k") != pub or b"v" not in reply:
                continue
            try:
                seq = int(reply[b"seq"])
                Ed25519PublicKey.from_public_bytes(pub).verify(reply[b"sig"], signed(salt, seq, reply[b"v"]))
            except (InvalidSignature, KeyError, ValueError):
                continue
            if best is None or seq > best[0]:
                best = (seq, reply[b"v"])
        return best

    async def put(self, key: Ed25519PrivateKey, salt: bytes, value: bytes) -> int:
        """Store a mutable item on the nodes closest to it; returns how many took it."""
        pub = public(key)
        target, seq = hashlib.sha1(pub + salt).digest(), int(time.time() * 1000)
        sig = key.sign(signed(salt, seq, value))
        closest = [(a, r[b"token"]) for _n, a, r in await self.lookup(target, b"get", {b"target": target})
                   if b"token" in r][:K]
        args = {"k": pub, "seq": seq, "sig": sig, "v": value, **({"salt": salt} if salt else {})}
        replies = await asyncio.gather(*(self.query(a, b"put", {**args, "token": t}) for a, t in closest))
        return sum(r is not None for r in replies)

    # ── upkeep ──
    async def maintain(self) -> None:
        """Every few minutes: rotate tokens, forget old items and silent nodes, keep our id BEP 42-correct."""
        while True:
            await asyncio.sleep(300)
            self._secrets = [os.urandom(16), self._secrets[0]]
            now = time.time()
            self.items = {t: i for t, i in self.items.items() if now - i["at"] < ITEM_SECONDS}
            for info_hash in list(self.peers):
                self.peers[info_hash] = {p: at for p, at in self.peers[info_hash].items() if now - at < PEER_SECONDS}
                if not self.peers[info_hash]:
                    del self.peers[info_hash]
            self.table = {n: e for n, e in self.table.items() if e[2] < 3}
            outside = self.external()
            if outside and not is_secure(self.id, outside[0]):
                self.id = secure_id(outside[0])
                logger.info("按公网地址换了节点号（BEP 42）")
            if len(self.table) < 4 * K:
                await self.join()
            else:
                somewhere = os.urandom(20)
                await self.lookup(somewhere, b"find_node", {b"target": somewhere})
            self.save()
