"""Fixed inputs → outputs that the Android and iPhone sides must reproduce (their unit tests read vectors.json).

Regenerate after changing protocol.md: ``.venv\\Scripts\\python.exe -m tests.vectors``.
"""
import json
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from mobile.bencode import encode
from mobile.session import Session, session_keys
from mobile.crypto import dht_key, hk, link_key, seal
from mobile.dht import public, secure_id, signed

FILE = Path(__file__).with_name("vectors.json")


def _x(seed: int):
    private = X25519PrivateKey.from_private_bytes(bytes([seed]) * 32)
    return private, private.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def vectors() -> dict:
    pc, pc_pub = _x(1)
    ph, ph_pub = _x(2)
    secret = bytes(range(32))
    link = link_key(ph, ph_pub, pc_pub, secret, pc_side=False)
    assert link == link_key(pc, pc_pub, ph_pub, secret, pc_side=True)
    dht = dht_key(link, 20378)
    e_phone, e_phone_pub = _x(3)
    e_pc, e_pc_pub = _x(4)
    sid = b"\x01\x02\x03\x04"
    to_pc, to_phone = session_keys(e_phone, e_pc_pub, link, sid, e_phone_pub, e_pc_pub)
    assert (to_pc, to_phone) == session_keys(e_pc, e_phone_pub, link, sid, e_phone_pub, e_pc_pub)
    packets = []
    session = Session(lambda data, address: packets.append(data), sid, to_pc, to_phone, None)
    session.message({"type": "say", "text": "你好"})
    return {
        "hk": {"ikm": b"ikm".hex(), "info": b"info".hex(), "out": hk(b"ikm", b"info").hex()},
        "seal": {"key": bytes(range(32)).hex(), "nonce": bytes(12).hex(), "aad": b"WM1".hex(),
                 "json": '{"type":"where","t":1,"id":"x"}',
                 "out": seal(bytes(range(32)), {"type": "where", "t": 1, "id": "x"}, b"WM1", nonce=bytes(12)).hex()},
        "link": {"pc_private": (bytes([1]) * 32).hex(), "pc_public": pc_pub.hex(),
                 "phone_private": (bytes([2]) * 32).hex(), "phone_public": ph_pub.hex(),
                 "secret": secret.hex(), "key": link.hex()},
        "dht": {"day": 20378, "public": public(dht).hex(), "signed": signed(b"pc", 5, b"v").hex(),
                "signature": dht.sign(signed(b"pc", 5, b"v")).hex(),
                "secure_id": secure_id("124.31.75.21", bytes(19) + b"\x01").hex()},
        "session": {"sid": sid.hex(), "phone_e_private": (bytes([3]) * 32).hex(), "phone_e_public": e_phone_pub.hex(),
                 "pc_e_private": (bytes([4]) * 32).hex(), "pc_e_public": e_pc_pub.hex(),
                 "to_pc": to_pc.hex(), "to_phone": to_phone.hex(), "first_message_packet": packets[0].hex()},
        "bencode": {"value": "d1:ad2:id3:abce1:q4:ping1:t2:aa1:y1:qe",
                    "encoded": encode({"t": b"aa", "y": "q", "q": "ping", "a": {"id": b"abc"}}).hex()},
    }


if __name__ == "__main__":
    FILE.write_text(json.dumps(vectors(), ensure_ascii=False, indent=1), encoding="utf-8")
    print(FILE)
