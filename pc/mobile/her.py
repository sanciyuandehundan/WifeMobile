"""Her side of a call: the phone becomes his microphone (WifeBody's speech recognizer) and her speaker (add-on API)."""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path

import websockets
from wife_addon import Client

from .call import Call
from .voice import MicIn, VoiceOut

logger = logging.getLogger("mobile.her")

ADDON_ID = "wife-mobile"
ASR_URL = "ws://127.0.0.1:9881/v1/mic/remote?name=%E6%89%8B%E6%9C%BA"     # name=手机
AWAY = {"他在哪": "不在电脑前，在外面用手机和她说话"}
TOPICS = ["reply.delta", "reply.final", "reply.cancel"]


class Her:
    def __init__(self, call: Call, binding: Path, near: bool) -> None:
        self.call, self.binding, self.near = call, binding, near
        self.loop = asyncio.get_running_loop()
        self.client: Client | None = None
        self.voice = VoiceOut(call.audio, self._played)
        self.mic = MicIn()
        self._heard: asyncio.Queue = asyncio.Queue(maxsize=100)
        self._tasks: list[asyncio.Task] = []
        call.on_audio, call.on_message = self._audio, self._message

    def start(self) -> None:
        self._tasks = [asyncio.create_task(self.voice.run()), asyncio.create_task(self._microphone()),
                       asyncio.create_task(self._connect())]

    def close(self) -> None:
        for task in self._tasks:
            task.cancel()
        client, self.client = self.client, None
        if client is not None:
            pending = list(self.voice.pending)
            asyncio.get_running_loop().run_in_executor(None, self._goodbye, client, pending)

    @staticmethod
    def _goodbye(client: Client, pending: list[int]) -> None:
        for stream_id in pending:                     # the call dropped in the middle of her sentence
            try:
                client.playback_finished(stream_id, False)
            except Exception:
                pass
        client.close()

    # ── Wife ──
    async def _connect(self) -> None:
        """Connects to Wife, then asks every 10 s whether she is still awake: if she was put to rest and woken
        during the call, the SDK's next request reconnects and subscribes again."""
        awake = None
        while not self.call.closed:
            try:
                if self.client is None:
                    await asyncio.to_thread(self._subscribe)
                else:
                    await asyncio.to_thread(self.client.health)
                if awake is False:
                    self.call.message({"type": "state", "awake": True})
                awake = True
            except Exception as exc:
                if awake is not False:
                    logger.info("Wife 没有醒着（%s），过一会儿再试", exc)
                    self.call.message({"type": "state", "awake": False})
                awake = False
            await asyncio.sleep(10)

    def _subscribe(self) -> None:
        client = Client.from_binding(ADDON_ID, self.binding, timeout=10)
        later = self.loop.call_soon_threadsafe
        client.on("audio.start", lambda b: later(self.voice.start, b["stream_id"], int(b.get("sample_rate", 32000)),
                                                 int(b.get("channels", 1))))
        client.on("audio.chunk", lambda b: later(self.voice.chunk, b["stream_id"], b["data"]))
        client.on("audio.end", lambda b: later(self.voice.end, b["stream_id"]))
        client.on("audio.cancel", lambda b: later(self.voice.cancel, b["stream_id"]))
        for topic in ("reply.delta", "reply.final"):
            client.on(topic, lambda b, final=topic == "reply.final": later(
                self.call.message, {"type": "reply", "turn": b.get("turn_id"), "text": b.get("text", ""),
                                    "final": final}))
        client.on("reply.cancel", lambda b: later(self.call.message, {"type": "cancel", "turn": b.get("turn_id")}))
        name = client.health().get("name", "")
        self._listen(client)
        self.client = client
        later(self.call.message, {"type": "state", "name": name, "awake": True})
        later(lambda: self._tasks.append(asyncio.create_task(self._card())))

    def _listen(self, client: Client) -> None:
        """Her voice goes to the phone only; away from the computer, she knows he is out."""
        client.subscribe(topics=TOPICS, audio=True, audio_owner=True, context=None if self.near else AWAY)

    async def _card(self) -> None:
        """Her prompt and what seldom changes about her and him, for the phone to be her when it can't reach us."""
        while not self.call.closed and self.client is not None:
            try:
                card = await asyncio.to_thread(self.client.request, "offline")
                self.call.message({"type": "card", **card})
            except Exception as exc:
                logger.info("没拿到离线资料：%s", exc)
            await asyncio.sleep(600)

    def _played(self, stream_id: int) -> None:
        if self.client is not None:
            self.loop.run_in_executor(None, self.client.playback_finished, stream_id, True)

    # ── the phone ──
    def _message(self, message: dict) -> None:
        kind = message.get("type")
        if kind == "hello" and bool(message.get("near", True)) != self.near:
            self.near = bool(message.get("near", True))
            if self.client is not None:
                self.loop.run_in_executor(None, self._listen, self.client)
        elif kind == "text" and str(message.get("text", "")).strip() and self.client is not None:
            text, client = str(message["text"]).strip()[:500], self.client
            self.loop.run_in_executor(None, lambda: client.text(text, present=True, wait=False))

    def _audio(self, seq: int, frame: bytes) -> None:
        pcm = self.mic.frame(seq, frame)
        if pcm and not self._heard.full():
            self._heard.put_nowait(pcm)

    async def _microphone(self) -> None:
        """His voice from the phone into Wife's recognizer, as if he spoke into the computer's microphone."""
        while not self.call.closed:
            try:
                async with websockets.connect(ASR_URL, max_size=None, compression=None) as ws:
                    logger.info("手机成了他的麦克风")
                    while True:
                        await ws.send(await self._heard.get())
            except (OSError, websockets.WebSocketException) as exc:
                logger.debug("语音识别没连上：%s", exc)
                await asyncio.sleep(3)
