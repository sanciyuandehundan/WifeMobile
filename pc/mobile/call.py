"""One call's packets (protocol.md "通话"): encrypted with the call's keys, pings, Opus frames, reliable messages."""
from __future__ import annotations

import asyncio
import json
import time

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

from .crypto import hk

PING, PONG, AUDIO, MESSAGE, ACK, BYE = 1, 2, 3, 4, 5, 6
KEEPALIVE, DEAD, RESEND, PIECE = 5.0, 20.0, 0.3, 1000


def call_keys(private: X25519PrivateKey, peer: bytes, link: bytes, sid: bytes, e_phone: bytes,
              e_pc: bytes) -> tuple[bytes, bytes]:
    """(phone→computer, computer→phone)."""
    keys = hk(private.exchange(X25519PublicKey.from_public_bytes(peer)) + link, b"call" + sid + e_phone + e_pc, 64)
    return keys[:32], keys[32:]


class Call:
    def __init__(self, send, sid: bytes, send_key: bytes, recv_key: bytes, addr, *, on_message=lambda m: None,
                 on_audio=lambda seq, frame: None, on_closed=lambda: None) -> None:
        self._send, self.sid, self.addr = send, sid, addr
        self._out, self._in = ChaCha20Poly1305(send_key), ChaCha20Poly1305(recv_key)
        self.on_message, self.on_audio, self.on_closed = on_message, on_audio, on_closed
        self._n = 0
        self._top, self._recent = 0, set()
        self._next_out, self._unacked = 0, {}
        self._next_in, self._early, self._joined = 1, {}, bytearray()
        self._audio = 0
        self.heard = time.monotonic()
        self.closed = False

    # ── sending ──
    def _packet(self, kind: int, body: bytes = b"") -> None:
        if self.closed:
            return
        self._n += 1
        header = b"WC" + self.sid + self._n.to_bytes(8, "big")
        self._send(header + self._out.encrypt(b"\0\0\0\0" + header[6:], bytes([kind]) + body, header), self.addr)

    def ping(self) -> None:
        self._packet(PING, int(time.time() * 1000).to_bytes(8, "big"))

    def audio(self, frame: bytes) -> None:
        self._audio = (self._audio + 1) & 0xFFFF
        self._packet(AUDIO, self._audio.to_bytes(2, "big") + frame)

    def message(self, message: dict) -> None:
        """Reliable and in order. Long ones go in 1000-byte pieces: a bigger datagram gets cut up on the way and
        mobile networks often lose one of the parts."""
        data = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode()
        pieces = [data[i:i + PIECE] for i in range(0, len(data), PIECE)] or [b""]
        for i, piece in enumerate(pieces):
            self._next_out += 1
            body = self._next_out.to_bytes(4, "big") + bytes([i < len(pieces) - 1]) + piece
            self._unacked[self._next_out] = [body, time.monotonic()]
            self._packet(MESSAGE, body)

    def hang_up(self) -> None:
        self._packet(BYE)
        self.close()

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            self.on_closed()

    # ── receiving ──
    def _fresh(self, n: int) -> bool:
        if n > self._top:
            return True
        return n > self._top - 64 and n not in self._recent

    def received(self, data: bytes, addr) -> None:
        if len(data) < 31 or self.closed:
            return
        header, n = data[:14], int.from_bytes(data[6:14], "big")
        if not self._fresh(n):
            return
        try:
            plain = self._in.decrypt(b"\0\0\0\0" + header[6:], data[14:], header)
        except InvalidTag:
            return
        self._top = max(self._top, n)
        self._recent = {m for m in self._recent if m > self._top - 64} | {n}
        self.addr, self.heard = addr, time.monotonic()      # follow him when his phone changes networks
        kind, body = plain[0], plain[1:]
        if kind == PING:
            self._packet(PONG, body)
        elif kind == AUDIO:
            self.on_audio(int.from_bytes(body[:2], "big"), body[2:])
        elif kind == MESSAGE:
            self._deliver(int.from_bytes(body[:4], "big"), body[4:])
        elif kind == ACK:
            upto = int.from_bytes(body[:4], "big")
            self._unacked = {s: v for s, v in self._unacked.items() if s > upto}
        elif kind == BYE:
            self.close()

    def _deliver(self, seq: int, data: bytes) -> None:
        if seq >= self._next_in:
            self._early[seq] = data
        while self._next_in in self._early:
            piece = self._early.pop(self._next_in)
            self._next_in += 1
            self._joined += piece[1:]
            if piece[0] == 0:
                joined, self._joined = bytes(self._joined), bytearray()
                self.on_message(json.loads(joined))
        self._packet(ACK, (self._next_in - 1).to_bytes(4, "big"))

    async def run(self, *, keepalive: bool) -> None:
        """Resend unconfirmed messages, ping every 5 s (the phone keeps its NAT open), hang up after 20 s of silence."""
        last_ping = 0.0
        while not self.closed:
            await asyncio.sleep(0.1)
            now = time.monotonic()
            if now - self.heard > DEAD:
                self.close()
                return
            for entry in self._unacked.values():
                if now - entry[1] > RESEND:
                    entry[1] = now
                    self._packet(MESSAGE, entry[0])
            if keepalive and now - last_ping > KEEPALIVE:
                last_ping = now
                self.ping()
