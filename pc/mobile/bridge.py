"""Runs the Link on its own asyncio thread and lets the Qt window talk to it."""
from __future__ import annotations

import asyncio
import concurrent.futures
import logging
import sys
import threading
import winreg
from pathlib import Path

from PySide6.QtCore import QObject, Signal

from .her import Her
from .link import Link
from .store import Store

logger = logging.getLogger("mobile.bridge")
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


class Bridge(QObject):
    paired = Signal(str)
    changed = Signal()
    show = Signal()

    def __init__(self, root: Path) -> None:
        super().__init__()
        self.root = root
        self.binding = root / "wife-binding.json"
        self.store = Store(root / "data")
        self.loop = asyncio.new_event_loop()
        self.link = Link(self.store, on_call=self._call, on_ended=self._ended, on_paired=lambda d: self.paired.emit(d.name),
                         on_show=self.show.emit)
        self.talking: dict = {}                    # call -> (Her, device)

    def start(self) -> None:
        """Raises OSError when another copy already holds the port."""
        ready = concurrent.futures.Future()

        def run() -> None:
            asyncio.set_event_loop(self.loop)
            try:
                self.loop.run_until_complete(self.link.start())
            except Exception as exc:
                ready.set_exception(exc)
                return
            ready.set_result(None)
            self.loop.run_forever()

        threading.Thread(target=run, name="link", daemon=True).start()
        ready.result(10)
        if not self.binding.is_file():
            threading.Thread(target=self._bind, daemon=True).start()

    def _bind(self) -> None:
        """WifeMobile sits next to WifeBody: register with Wife once, so her add-on page lists it."""
        wife = self.root.parent.parent / "WifeBody"
        if (wife / "wife_manager.py").is_file():
            from wife_addon.binding import bind
            try:
                bind(wife, self.root / "wife-addon.json")
                logger.info("已和 Wife 绑定")
            except Exception:
                logger.exception("和 Wife 绑定失败")

    def stop(self) -> None:
        self.run(self.link.stop)
        self.loop.call_soon_threadsafe(self.loop.stop)

    def run(self, function, *args):
        """Call ``function`` on the link's thread and wait for its result."""
        future = concurrent.futures.Future()

        def call() -> None:
            try:
                future.set_result(function(*args))
            except Exception as exc:
                future.set_exception(exc)

        self.loop.call_soon_threadsafe(call)
        return future.result(5)

    # ── calls (link thread) ──
    def _call(self, call, device, near: bool) -> None:
        her = Her(call, self.binding, near)
        her.start()
        self.talking[call] = (her, device)
        self.changed.emit()

    def _ended(self, call) -> None:
        entry = self.talking.pop(call, None)
        if entry:
            entry[0].close()
        self.changed.emit()

    def calls(self) -> list[tuple[str, bool]]:
        return self.run(lambda: [(device.name, her.near) for her, device in self.talking.values()])

    # ── settings ──
    def forget(self, dev: bytes) -> None:
        def forget() -> None:
            for call, (_her, device) in list(self.talking.items()):
                if device.dev == dev:
                    call.hang_up()
            self.store.forget(dev)
        self.run(forget)
        self.changed.emit()

    def here(self) -> dict:
        return self.run(self.link.here)

    @staticmethod
    def starts_with_windows() -> bool:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
                winreg.QueryValueEx(key, "WifeMobile")
                return True
        except OSError:
            return False

    def set_start_with_windows(self, on: bool) -> None:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if on:
                pythonw = Path(sys.executable).with_name("pythonw.exe")
                winreg.SetValueEx(key, "WifeMobile", 0, winreg.REG_SZ, f'"{pythonw}" "{self.root / "main.py"}"')
            else:
                winreg.DeleteValue(key, "WifeMobile")
