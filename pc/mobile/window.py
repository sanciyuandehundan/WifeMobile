"""The pairing window: a one-time code, the paired phones, and how the phone finds this computer when he is out."""
from __future__ import annotations

import time

import segno
from PySide6.QtCore import QRectF, Qt, QTimer
from PySide6.QtGui import QColor, QImage, QPainter, QPixmap
from PySide6.QtWidgets import QButtonGroup, QLabel, QPushButton, QRadioButton, QVBoxLayout, QWidget
from wife_addon.style import AddonWindow, control_row, label, section, toggle_row

from .link import PAIRING_SECONDS

FIND = [("ipv6", "IPv6 直连", "不经过任何人。家里宽带要有 IPv6，光猫或路由器放行 UDP 18766。"
                                "你在外面时家里地址变了，要等回家连上 WiFi 才会更新。"),
        ("dht", "BT 网络（引导节点）", "平时直接拨；拨不通时，手机去 BT 的 DHT 查一次电脑的新地址。"
                                     "电脑开着时也替别的 BT 用户回话、存东西。"),
        ("pair", "回家重新配对", "只用配对时记下的地址，什么都不自动更新；连不上就回家重新扫码。")]


def qr_pixmap(text: str, size: int = 280) -> QPixmap:
    matrix = [list(row) for row in segno.make(text, error="m").matrix_iter(border=3)]
    image = QImage(size, size, QImage.Format.Format_RGB32)
    image.fill(QColor("white"))
    cell = size / len(matrix)
    painter = QPainter(image)
    painter.setPen(Qt.PenStyle.NoPen)
    painter.setBrush(QColor("#2b2220"))
    for y, row in enumerate(matrix):
        for x, dark in enumerate(row):
            if dark:
                painter.drawRect(QRectF(x * cell, y * cell, cell + 0.5, cell + 0.5))
    painter.end()
    return QPixmap.fromImage(image)


class Window(AddonWindow):
    def __init__(self, bridge) -> None:
        super().__init__("手机", "用手机当她的耳机和麦克风；不在电脑旁也能和她说话。", mark="机")
        self.bridge = bridge
        self._until = 0.0

        rows = section("配对新手机", self.body)
        self.show_code = QPushButton("显示配对码")
        self.show_code.clicked.connect(self._begin)
        rows.add_row(control_row("配对码", "只显示这一次：手机扫过、5 分钟后或关掉窗口就作废，以后也看不到。"
                                         "要再配一部手机就再生成一个。手机和电脑要连着同一个 WiFi。", self.show_code))
        self.code = QLabel()
        self.code.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.code_note = label("", "managementSmall")
        self.code_note.setAlignment(Qt.AlignmentFlag.AlignCenter)
        holder = QWidget()
        column = QVBoxLayout(holder)
        column.setContentsMargins(20, 10, 20, 16)
        column.addWidget(self.code)
        column.addWidget(self.code_note)
        self.code_box = holder
        holder.hide()
        rows.add_row(holder)

        self.devices = section("配对过的手机", self.body)

        find = section("你不在电脑旁时，手机怎么找到电脑", self.body)
        self.choices = QButtonGroup(self)
        for value, title, description in FIND:
            button = QRadioButton()
            button.setChecked(bridge.store.find == value)
            button.toggled.connect(lambda on, value=value: on and self._find(value))
            self.choices.addButton(button)
            find.add_row(control_row(title, description, button))
        self.address = label("", "managementSmall")
        find.add_row(control_row("这台电脑现在的外出地址", "", self.address))

        other = section("其他", self.body)
        start = toggle_row(other, "开机自动运行", "登录 Windows 后在后台等手机打来；关掉这个窗口也会继续等。",
                           bridge.starts_with_windows())
        start.toggled.connect(bridge.set_start_with_windows)

        self.add_button("关闭", self.close, primary=True)
        self._ticker = QTimer(self, interval=1000, timeout=self._tick)
        bridge.paired.connect(self._paired)
        bridge.changed.connect(self.refresh)
        self.refresh()

    def present(self) -> None:
        self.refresh()
        self.show()
        self.raise_()
        self.activateWindow()

    def refresh(self) -> None:
        calls = self.bridge.calls()
        if calls:
            self.set_state("● 正在通话：" + "、".join(f"{n}（{'在电脑旁' if near else '在外面'}）" for n, near in calls),
                           lit=True)
        else:
            self.set_state("○ 现在没有手机连着")
        while self.devices.rows.count():
            item = self.devices.rows.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        for device in sorted(self.bridge.store.devices.values(), key=lambda d: d.paired):
            forget = QPushButton("忘记")
            forget.clicked.connect(lambda _=False, dev=device.dev: self.bridge.forget(dev))
            self.devices.add_row(control_row(device.name, f"{time.strftime('%Y-%m-%d', time.localtime(device.paired))}"
                                             " 配对。忘记以后它就再也连不上，要重新扫码。", forget))
        if not self.bridge.store.devices:
            self.devices.add_row(control_row("还没有", "点上面的“显示配对码”，用手机上的 Wife 扫一下。", QWidget()))
        away = self.bridge.here()["away"]
        self.address.setText("、".join(away) if away else "没有（这个网络没有 IPv6）：外出时手机连不上")

    def _find(self, value: str) -> None:
        self.bridge.store.set(find=value)
        self.status.setText("改好了。手机下次在家连上 WiFi、或下次通话时会知道。")
        QTimer.singleShot(1500, self.refresh)

    # ── the one-time code ──
    def _begin(self) -> None:
        text = self.bridge.run(self.bridge.link.begin_pairing)
        self.code.setPixmap(qr_pixmap(text))
        self._until = time.monotonic() + PAIRING_SECONDS
        self.code_box.show()
        self.show_code.setText("换一个")
        self._ticker.start()
        self._tick()

    def _tick(self) -> None:
        left = int(self._until - time.monotonic())
        if left <= 0:
            self._end("配对码过期了，没有用过。")
        else:
            self.code_note.setText(f"用手机上的 Wife 扫这个码。{left // 60}:{left % 60:02d} 后作废。")

    def _end(self, note: str) -> None:
        self._ticker.stop()
        self.bridge.run(self.bridge.link.end_pairing)
        self.code.clear()
        self.code_box.hide()
        self.show_code.setText("显示配对码")
        self.status.setText(note)

    def _paired(self, name: str) -> None:
        self._end(f"配对好了：{name}。这个配对码已经作废。")
        self.refresh()

    def hideEvent(self, event) -> None:              # closed, Esc, or hidden by the tray: the code is gone
        if self._ticker.isActive():
            self._end("关掉窗口，配对码作废了。")
        super().hideEvent(event)
