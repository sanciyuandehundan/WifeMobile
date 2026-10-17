"""WifeMobile 的电脑端：在托盘里等手机打来。--settings 打开配对窗口（已经在运行时，让那一份把窗口拿出来）。"""
from __future__ import annotations

import logging
import socket
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main() -> int:
    settings = "--settings" in sys.argv
    (ROOT / "data").mkdir(exist_ok=True)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s",
                        handlers=[RotatingFileHandler(ROOT / "data" / "mobile.log", maxBytes=2_000_000,
                                                      backupCount=1, encoding="utf-8")])
    from PySide6.QtGui import QIcon
    from PySide6.QtWidgets import QApplication, QMenu, QStyle, QSystemTrayIcon

    from mobile.bridge import Bridge
    from mobile.net import PORT
    from mobile.window import Window

    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    bridge = Bridge(ROOT)
    try:
        bridge.start()
    except OSError:                                   # already running: ask it to show its window
        if settings:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.sendto(b"WSHOW", ("127.0.0.1", PORT))
        return 0
    window = Window(bridge)
    bridge.show.connect(window.present)
    icon_file = ROOT.parent.parent / "WifeBody" / "wife_icon.png"
    icon = QIcon(str(icon_file)) if icon_file.is_file() else app.style().standardIcon(QStyle.StandardPixmap.SP_ComputerIcon)
    window.setWindowIcon(icon)
    tray = QSystemTrayIcon(icon)
    tray.setToolTip("Wife 手机端：等手机打来")
    menu = QMenu()
    menu.addAction("配对和设置", window.present)
    menu.addAction("退出", lambda: (bridge.stop(), app.quit()))
    tray.setContextMenu(menu)
    tray.activated.connect(lambda reason: reason == QSystemTrayIcon.ActivationReason.Trigger and window.present())
    tray.show()
    if settings:
        window.present()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
