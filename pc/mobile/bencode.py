"""Bencode, as the BitTorrent DHT speaks it. Strings decode to bytes; dict keys are bytes."""
from __future__ import annotations


def encode(value) -> bytes:
    if isinstance(value, bool):
        raise TypeError("bencode has no booleans")
    if isinstance(value, int):
        return b"i%de" % value
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, (bytes, bytearray)):
        return b"%d:%s" % (len(value), bytes(value))
    if isinstance(value, (list, tuple)):
        return b"l" + b"".join(encode(v) for v in value) + b"e"
    if isinstance(value, dict):
        items = sorted(((k.encode() if isinstance(k, str) else k), v) for k, v in value.items())
        return b"d" + b"".join(encode(k) + encode(v) for k, v in items) + b"e"
    raise TypeError(f"cannot bencode {type(value).__name__}")


def decode(data: bytes):
    value, end = _decode(data, 0)
    if end != len(data):
        raise ValueError("trailing data after bencoded value")
    return value


def _decode(data: bytes, i: int):
    kind = data[i:i + 1]
    if kind == b"i":
        end = data.index(b"e", i)
        return int(data[i + 1:end]), end + 1
    if kind == b"l":
        items, i = [], i + 1
        while data[i:i + 1] != b"e":
            item, i = _decode(data, i)
            items.append(item)
        return items, i + 1
    if kind == b"d":
        items, i = {}, i + 1
        while data[i:i + 1] != b"e":
            key, i = _decode(data, i)
            items[key], i = _decode(data, i)
        return items, i + 1
    if kind.isdigit():
        colon = data.index(b":", i)
        start = colon + 1
        end = start + int(data[i:colon])
        if end > len(data):
            raise ValueError("string runs past the end")
        return data[start:end], end
    raise ValueError(f"bad bencode at {i}")
