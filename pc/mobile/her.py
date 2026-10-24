"""Her side for his phones: one link to Wife (the add-on API) for all of them. What he sends from a phone goes to her,
what she says goes to his phone when it should (protocol.md 送到), and during a call the phone is his microphone
(WifeBody's speech recognizer) and her speaker."""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import websockets
from wife_addon import Client, WifeError

from .chat import Outbox, item_id, now_ms
from .crypto import unb64
from .session import Session
from .store import Device
from .voice import MicIn, VoiceOut, encode_her, her_recording, his_recording, wav

logger = logging.getLogger("mobile.her")

ADDON_ID = "wife-mobile"
ASR_URL = "ws://127.0.0.1:9881/v1/mic/remote?name=%E6%89%8B%E6%9C%BA"     # name=手机
TOPICS = ["reply.delta", "reply.final", "reply.cancel", "presence"]
TURN_SECONDS = 130
# what she is told about where he is, in her input's 【附属应用】 (WifeBody add-on API "context")
ON_PHONE = {"他在哪": "在用手机和她说话"}
OUT = {"他在哪": "不在电脑前，在用手机和她说话"}
REACHABLE = {"他在哪": "不在电脑前，她说的话会发到他手机上"}


@dataclass
class Phone:
    device: Device
    outbox: Outbox
    session: Session | None = None
    app: str = "standby"                      # hello: open (in front), standby (Android's service), fetch (iOS)
    talked: list = field(default_factory=list)          # his items waiting for her, one at a time

    @property
    def connected(self) -> bool:
        return self.session is not None and not self.session.closed

    def send(self, message: dict) -> None:
        if self.connected:
            self.session.message(message)


@dataclass
class Call:
    phone: Phone
    near: bool
    voice: VoiceOut
    mic: MicIn = field(default_factory=MicIn)
    heard: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=100))
    tasks: list = field(default_factory=list)


class Her:
    def __init__(self, binding: Path, folder: Path) -> None:
        self.binding, self.folder = binding, folder
        self.loop = asyncio.get_running_loop()
        self.client: Client | None = None
        self.name, self.awake, self.away, self.voiceprints = "", None, False, 0
        self.card: dict | None = None
        self.phones: dict[bytes, Phone] = {}
        self.call: Call | None = None
        self._asking: Phone | None = None          # whose words she is answering (her reply as it is written goes there)
        self._streams: dict[int, dict] = {}        # her voice for words said while he is away, by stream
        self._turns: dict[int, tuple[dict, list[Phone]]] = {}   # turn → her item and the phones that got it
        self._tasks: list[asyncio.Task] = []

    def start(self) -> None:
        self._tasks = [asyncio.create_task(self._connect()), asyncio.create_task(self._fetch_card())]

    def stop(self) -> None:
        self._end_call()
        for task in self._tasks:
            task.cancel()
        if self.client is not None:
            self.client.close()

    # ── phones (from the Link) ──
    def connected(self, session: Session, device: Device) -> None:
        phone = self._phone(device)
        phone.session, phone.app = session, "standby"
        session.on_message = lambda message: self._message(phone, message)
        session.on_audio = lambda seq, frame: self._audio(phone, seq, frame)
        session.message(self._state())
        if self.card:
            session.message(self.card)

    def ended(self, session: Session, device: Device) -> None:
        phone = self.phones.get(device.dev)
        if phone is not None and phone.session is session:
            phone.session = None
            if self.call is not None and self.call.phone is phone:
                self._end_call()
            self._listen()

    def forget(self, device: Device) -> None:
        self.phones.pop(device.dev, None)

    def _phone(self, device: Device) -> Phone:
        if device.dev not in self.phones:
            self.phones[device.dev] = Phone(device, Outbox(self.folder / device.dev.hex()))
        return self.phones[device.dev]

    def _state(self) -> dict:
        return {"type": "state", "name": self.name, "awake": bool(self.awake), "away": self.away,
                "voiceprint": self.voiceprints}

    def _tell_all(self, message: dict) -> None:
        for phone in self.phones.values():
            phone.send(message)

    # ── what the phone says ──
    def _message(self, phone: Phone, message: dict) -> None:
        kind = message.get("type")
        if kind == "hello":
            phone.app = str(message.get("app") or "open")
            for item in phone.outbox.waiting():
                phone.send(item)
            self._listen()
        elif kind == "pace" and phone.session is not None:
            phone.session.ping_every = float(message.get("ping") or 5)
        elif kind == "got":
            phone.outbox.got(message.get("ids") or [])
        elif kind in ("say", "voice", "photo") and message.get("id"):
            phone.talked.append(message)
            if len(phone.talked) == 1:
                asyncio.create_task(self._talk(phone))
        elif kind == "call":
            self._end_call()
            if message.get("on"):
                self._start_call(phone, bool(message.get("near", True)))
            self._listen()
        elif kind == "enroll":
            asyncio.create_task(self._enroll(phone, message))

    async def _talk(self, phone: Phone) -> None:
        """His items one after another, the way he sent them. One she can't take now (Wife asleep or busy) stays on
        his phone unconfirmed, and the phone sends it again when she is awake."""
        while phone.talked:
            item = phone.talked[0]
            try:
                if self.client is not None and phone.outbox.seen(item["id"]):
                    self._got(phone, item)
                elif self.client is not None:
                    await self._answer(phone, item)
            except Exception as exc:
                logger.warning("没能把他从手机发来的%s交给她：%s", {"say": "话", "voice": "语音", "photo": "照片"}[item["type"]],
                               exc)
            phone.talked.pop(0)

    @staticmethod
    def _got(phone: Phone, item: dict) -> None:
        phone.outbox.remember(item["id"])
        phone.send({"type": "got", "ids": [item["id"]]})

    async def _answer(self, phone: Phone, item: dict) -> None:
        client, kind = self.client, item["type"]
        if kind == "voice":
            heard = await asyncio.to_thread(client.request, "stt", {"speaker": "phone"},
                                            upload=wav(his_recording(item["opus"])), timeout=60)
            his = bool(heard.get("master", True))
            phone.send({"type": "heard", "id": item["id"], "text": heard["text"], "ok": his})
            if not his or not heard["text"]:       # not his voice, or nothing said: she does not take it
                return self._got(phone, item)
            text = heard["text"]
        elif kind == "photo":
            try:
                seen = (await asyncio.to_thread(client.request, "see", {"about": "他用手机拍了发给她的照片"},
                                                upload=unb64(item["jpeg"]), timeout=60))["text"]
                text = f"（他从手机发来一张照片：{seen}）"
            except WifeError:                      # low-VRAM mode or screen privacy: she can't look at pictures
                text = "（他从手机发来一张照片，她现在看不了图片）"
            text += str(item.get("text") or "")
        else:
            text = str(item.get("text") or "").strip()[:2000]
        self._asking = phone
        try:
            result = await asyncio.to_thread(client.request, "input", {
                "text": text, "input_type": "voice" if kind == "voice" else "text", "present": False, "voice": False,
                "wait": True}, timeout=TURN_SECONDS)
        finally:
            self._asking = None
        self._got(phone, item)
        reply = str(result.get("reply") or "").strip()
        if not result.get("accepted") or not reply:
            return
        mine = self._her([phone], reply)
        if kind == "voice" or item.get("voice"):  # he spoke to her, or asked for her voice: she answers with it too
            audio = await asyncio.to_thread(client.request, "tts", {"text": reply, "emotion": result.get("emotion")},
                                            timeout=90)
            mine["opus"], mine["ms"] = her_recording(audio.data)
            self._keep(mine, [phone])

    def _her(self, phones: list[Phone], text: str) -> dict:
        item = {"type": "her", "id": item_id(), "t": now_ms(), "text": text}
        self._keep(item, phones)
        return item

    def _keep(self, item: dict, phones: list[Phone]) -> None:
        for phone in phones:
            phone.outbox.put(item)
            phone.send(item)

    async def _enroll(self, phone: Phone, message: dict) -> None:
        try:
            if message.get("clear"):
                body, upload = {"action": "clear"}, None
            else:
                body, upload = {"action": "add"}, wav(his_recording(message["opus"]))
            self.voiceprints = (await asyncio.to_thread(self.client.request, "voiceprint", body, upload=upload,
                                                        timeout=60))["count"]
            phone.send({"type": "voiceprint", "count": self.voiceprints})
        except Exception as exc:
            phone.send({"type": "voiceprint", "count": self.voiceprints, "error": str(exc)[:200]})

    # ── a call: the phone is his microphone and her speaker ──
    def _start_call(self, phone: Phone, near: bool) -> None:
        call = self.call = Call(phone, near, VoiceOut(phone.session.audio, self._played))
        call.tasks = [asyncio.create_task(call.voice.run()), asyncio.create_task(self._microphone(call))]
        logger.info("%s 开始通话（%s）", phone.device.name, "在电脑旁" if near else "不在电脑前")

    def _end_call(self) -> None:
        call, self.call = self.call, None
        if call is None:
            return
        for task in call.tasks:
            task.cancel()
        for stream_id in list(call.voice.pending):      # the call ended in the middle of her sentence
            self._finished(stream_id, False)
        logger.info("%s 的通话结束", call.phone.device.name)

    def _audio(self, phone: Phone, seq: int, frame: bytes) -> None:
        call = self.call
        if call is not None and call.phone is phone:
            pcm = call.mic.frame(seq, frame)
            if pcm and not call.heard.full():
                call.heard.put_nowait(pcm)

    async def _microphone(self, call: Call) -> None:
        """His voice from the phone into Wife's recognizer, as if he spoke into the computer's microphone."""
        while self.call is call:
            try:
                async with websockets.connect(ASR_URL, max_size=None, compression=None) as ws:
                    logger.info("手机成了他的麦克风")
                    while True:
                        await ws.send(await call.heard.get())
            except (OSError, websockets.WebSocketException) as exc:
                logger.debug("语音识别没连上：%s", exc)
                await asyncio.sleep(3)

    # ── Wife ──
    async def _connect(self) -> None:
        """Connects to Wife, then asks every 10 s whether she is still awake: if she was put to rest and woken, the
        next request reconnects and subscribes again."""
        while True:
            try:
                if self.client is None:
                    await asyncio.to_thread(self._subscribe)
                else:
                    await asyncio.to_thread(self.client.health)
                if self.awake is not True:
                    self.awake = True
                    self._tell_all(self._state())
            except Exception as exc:
                if self.awake is not False:
                    logger.info("Wife 没有醒着（%s），过一会儿再试", exc)
                    self.awake = False
                    self._tell_all(self._state())
            await asyncio.sleep(10)

    def _subscribe(self) -> None:
        client = Client.from_binding(ADDON_ID, self.binding, timeout=10)
        later = self.loop.call_soon_threadsafe
        client.on("audio.start", lambda b: later(self._audio_start, b))
        client.on("audio.chunk", lambda b: later(self._audio_chunk, b["stream_id"], b["data"]))
        client.on("audio.end", lambda b: later(self._audio_end, b["stream_id"], True))
        client.on("audio.cancel", lambda b: later(self._audio_end, b["stream_id"], False))
        client.on("reply.delta", lambda b: later(self._reply, b, False))
        client.on("reply.final", lambda b: later(self._reply, b, True))
        client.on("reply.cancel", lambda b: later(self._tell_all, {"type": "cancel", "turn": b.get("turn_id")}))
        client.on("presence", lambda b: later(self._presence, bool(b.get("away"))))
        self.name = client.health().get("name", "")
        try:
            self.voiceprints = client.request("voiceprint", {"action": "count"})["count"]
        except WifeError:                          # speech recognition off, or a Wife from before phone voiceprints
            self.voiceprints = 0
        client.subscribe(**self._subscription())
        self.client = client

    def _subscription(self) -> dict:
        """Her voice comes here during a call (to the phone, live) and while he is away (recorded for his phone,
        silent on the computer); she is told where he is."""
        call, using = self.call, any(p.connected and p.app == "open" for p in self.phones.values())
        context = (None if call.near else OUT) if call else OUT if self.away and using else \
            REACHABLE if self.away else ON_PHONE if using else None
        audio = call is not None or self.away
        return {"topics": TOPICS, "audio": audio, "audio_owner": audio, "context": context}

    def _listen(self) -> None:
        client = self.client
        if client is not None:
            self.loop.run_in_executor(None, lambda: client.subscribe(**self._subscription()))

    def _presence(self, away: bool) -> None:
        if away != self.away:
            self.away = away
            logger.info("他%s电脑", "离开了" if away else "回到了")
            self._listen()
            self._tell_all(self._state())

    def _reply(self, body: dict, final: bool) -> None:
        """Her words as they are written. Her answer to what he sent from a phone goes to that phone (as `her`
        when _answer has it); anything else she says goes to the phone in a call, the phones open in front, and,
        while he is away from the computer, to every phone."""
        live = {"type": "reply", "turn": body.get("turn_id"), "text": body.get("text", ""), "final": final}
        if body.get("source") == ADDON_ID:
            if self._asking is not None:
                self._asking.send(live)
            return
        phones = [p for p in self.phones.values() if self.away or p.app == "open" and p.connected or
                  self.call is not None and self.call.phone is p]
        for phone in phones:
            phone.send(live)
        if final and phones and live["text"]:
            item = self._her(phones, live["text"])
            if self.away and self.call is None:        # her voice for it is being recorded
                self._turns[body.get("turn_id")] = (item, phones)

    def _audio_start(self, body: dict) -> None:
        if self.call is not None:
            self.call.voice.start(body["stream_id"], int(body.get("sample_rate", 32000)), int(body.get("channels", 1)))
        else:
            self._streams[body["stream_id"]] = {"turn": body.get("turn_id"), "rate": int(body.get("sample_rate", 32000)),
                                                "channels": int(body.get("channels", 1)), "pcm": bytearray()}

    def _audio_chunk(self, stream_id: int, data: bytes) -> None:
        if self.call is not None:
            self.call.voice.chunk(stream_id, data)
        elif stream_id in self._streams:
            self._streams[stream_id]["pcm"] += data

    def _audio_end(self, stream_id: int, whole: bool) -> None:
        if self.call is not None:
            (self.call.voice.end if whole else self.call.voice.cancel)(stream_id)
            return
        stream = self._streams.pop(stream_id, None)
        if stream is None:
            return
        self._finished(stream_id, whole)
        entry = self._turns.pop(stream["turn"], None)
        if whole and entry is not None and stream["pcm"]:
            item, phones = entry
            item["opus"], item["ms"] = encode_her(np.frombuffer(bytes(stream["pcm"]), dtype=np.int16), stream["rate"],
                                                  stream["channels"])
            self._keep(item, phones)

    def _played(self, stream_id: int) -> None:
        self._finished(stream_id, True)

    def _finished(self, stream_id: int, played: bool) -> None:
        if self.client is not None:
            self.loop.run_in_executor(None, self.client.playback_finished, stream_id, played)

    async def _fetch_card(self) -> None:
        """Her prompt and what seldom changes about her and him, for the phone to be her when it can't reach us."""
        while True:
            if self.client is not None:
                try:
                    self.card = {"type": "card", **await asyncio.to_thread(self.client.request, "offline")}
                    self._tell_all(self.card)
                except Exception as exc:
                    logger.info("没拿到离线资料：%s", exc)
            await asyncio.sleep(600 if self.card else 10)
