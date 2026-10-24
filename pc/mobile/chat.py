"""What she says to his phone stays here until the phone says it has it, and what came from his phone is answered once
(protocol.md 送到): one folder per phone in pc/data/chat, one file per item of hers."""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

from .crypto import b64

KEEP = 200                  # her items kept for a phone that doesn't come back; the oldest go first
SEEN = 500                  # ids of his items remembered, so one sent again is not answered twice


def item_id() -> str:
    return b64(os.urandom(8))


def now_ms() -> int:
    return int(time.time() * 1000)


class Outbox:
    def __init__(self, folder: Path) -> None:
        self.folder = folder
        folder.mkdir(parents=True, exist_ok=True)
        self._seen_file = folder / "seen.json"
        try:
            self._seen = json.loads(self._seen_file.read_text(encoding="utf-8"))
        except FileNotFoundError:
            self._seen = []

    def put(self, item: dict) -> None:
        """Her item, or the same id again with her voice added."""
        (self.folder / f"{item['id']}.json").write_text(json.dumps(item, ensure_ascii=False), encoding="utf-8")
        waiting = sorted(self.folder.glob("*.json"), key=os.path.getmtime)
        for old in [f for f in waiting if f != self._seen_file][:-KEEP]:
            old.unlink()

    def got(self, ids) -> None:
        for name in ids:
            if str(name).replace("-", "").replace("_", "").isalnum():          # an id, not a path
                (self.folder / f"{name}.json").unlink(missing_ok=True)

    def waiting(self) -> list[dict]:
        items = [json.loads(f.read_text(encoding="utf-8")) for f in self.folder.glob("*.json") if f != self._seen_file]
        return sorted(items, key=lambda item: item["t"])

    def seen(self, name: str) -> bool:
        return name in self._seen

    def remember(self, name: str) -> None:
        """His item `name` reached her: sent again, it is only confirmed."""
        self._seen = self._seen[-(SEEN - 1):] + [name]
        self._seen_file.write_text(json.dumps(self._seen), encoding="utf-8")
