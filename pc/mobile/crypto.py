"""The few primitives both ends share (protocol.md): HKDF, sealed JSON, the day's DHT signing key."""
from __future__ import annotations

import base64
import json
import os
import time

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SALT = b"wife-mobile/1"
FRESH_MS = 5 * 60 * 1000


def hk(ikm: bytes, info: bytes, n: int = 32) -> bytes:
    return HKDF(hashes.SHA256(), n, SALT, info).derive(ikm)


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def now_ms() -> int:
    return int(time.time() * 1000)


def stamp(message: dict) -> dict:
    return {**message, "t": now_ms(), "id": b64(os.urandom(8))}


def seal(key: bytes, message: dict, aad: bytes = b"", *, nonce: bytes | None = None) -> bytes:
    nonce = nonce or os.urandom(12)
    data = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode()
    return nonce + ChaCha20Poly1305(key).encrypt(nonce, data, aad)


def open_sealed(key: bytes, sealed: bytes, aad: bytes = b"") -> dict:
    """Raises cryptography's InvalidTag when the key or the data is wrong."""
    return json.loads(ChaCha20Poly1305(key).decrypt(sealed[:12], sealed[12:], aad))


class Seen:
    """Drops stale or replayed messages (their t is off by more than 5 minutes, or their id came before)."""

    def __init__(self) -> None:
        self._ids: dict[str, int] = {}

    def fresh(self, message: dict) -> bool:
        now, t, mid = now_ms(), int(message.get("t", 0)), str(message.get("id", ""))
        if abs(now - t) > FRESH_MS or not mid or mid in self._ids:
            return False
        self._ids = {k: v for k, v in self._ids.items() if now - v < 2 * FRESH_MS}
        self._ids[mid] = t
        return True


def x25519_pair() -> tuple[X25519PrivateKey, bytes]:
    private = X25519PrivateKey.generate()
    return private, private.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def link_key(private: X25519PrivateKey, own_pub: bytes, peer_pub: bytes, secret: bytes, *, pc_side: bool) -> bytes:
    shared = private.exchange(X25519PublicKey.from_public_bytes(peer_pub))
    pc_pub, ph_pub = (own_pub, peer_pub) if pc_side else (peer_pub, own_pub)
    return hk(shared + secret, b"link" + pc_pub + ph_pub)


def day(at: float | None = None) -> int:
    return int((time.time() if at is None else at) // 86400)


def dht_key(link: bytes, on_day: int) -> Ed25519PrivateKey:
    return Ed25519PrivateKey.from_private_bytes(hk(link, b"dht" + on_day.to_bytes(4, "big")))
