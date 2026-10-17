"""Paired phones and settings in pc/data. A phone's link key is kept encrypted with Windows DPAPI."""
from __future__ import annotations

import ctypes
import json
import time
from ctypes import wintypes
from dataclasses import dataclass
from pathlib import Path

from .crypto import b64, unb64

FIND = ("ipv6", "dht", "pair")


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _dpapi(data: bytes, protect: bool) -> bytes:
    buffer = ctypes.create_string_buffer(data, len(data))
    source, result = _Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char))), _Blob()
    call = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not call(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(result)):   # 1: never show a prompt
        raise ctypes.WinError()
    try:
        return ctypes.string_at(result.pbData, result.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(result.pbData)


@dataclass
class Device:
    dev: bytes
    name: str
    key: bytes
    paired: float


class Store:
    def __init__(self, folder: Path) -> None:
        self.folder = folder
        folder.mkdir(parents=True, exist_ok=True)
        self.devices: dict[bytes, Device] = {}
        self.settings = {"find": "ipv6", "name": ""}
        self._load()

    @property
    def dht_file(self) -> Path:
        return self.folder / "dht.json"

    @property
    def find(self) -> str:
        return self.settings["find"]

    def _load(self) -> None:
        try:
            self.settings.update(json.loads((self.folder / "settings.json").read_text(encoding="utf-8")))
        except FileNotFoundError:
            pass
        try:
            saved = json.loads((self.folder / "devices.json").read_text(encoding="utf-8"))
        except FileNotFoundError:
            saved = []
        for item in saved:
            dev = unb64(item["dev"])
            self.devices[dev] = Device(dev, item["name"], _dpapi(unb64(item["key"]), False), item["paired"])

    def _save(self) -> None:
        devices = [{"dev": b64(d.dev), "name": d.name, "key": b64(_dpapi(d.key, True)), "paired": d.paired}
                   for d in self.devices.values()]
        (self.folder / "devices.json").write_text(json.dumps(devices, ensure_ascii=False, indent=1), encoding="utf-8")
        (self.folder / "settings.json").write_text(json.dumps(self.settings, ensure_ascii=False, indent=1),
                                                   encoding="utf-8")

    def add(self, dev: bytes, name: str, key: bytes) -> Device:
        device = self.devices[dev] = Device(dev, name, key, time.time())
        self._save()
        return device

    def forget(self, dev: bytes) -> None:
        self.devices.pop(dev, None)
        self._save()

    def set(self, **values) -> None:
        if values.get("find", self.find) not in FIND:
            raise ValueError(f"find must be one of {FIND}")
        self.settings.update(values)
        self._save()
