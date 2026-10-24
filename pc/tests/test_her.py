"""Her side with a fake Wife and fake sessions: what he sends goes to her once, her words reach the right phone and
wait there until it has them, her voice comes along when it should."""
import asyncio
import io
import wave
from types import SimpleNamespace as NS

import numpy as np
import pytest

from mobile.her import ADDON_ID, OUT, REACHABLE, Her
from mobile.store import Device
from mobile.voice import encode_her


def clip(seconds: float = 0.5, rate: int = 32000) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes((3000 * np.sin(np.arange(int(rate * seconds)) / 4)).astype(np.int16).tobytes())
    return buffer.getvalue()


class Wife:
    def __init__(self) -> None:
        self.asked, self.subscribed, self.master = [], [], True

    def request(self, method, body=None, *, upload=None, timeout=None):
        self.asked.append((method, body))
        if method == "input":
            return {"accepted": True, "reply": "欢迎回来", "emotion": "happy"}
        if method == "stt":
            return {"text": "我到家了", "master": self.master}
        if method == "tts":
            return NS(data=clip())
        if method == "see":
            return {"text": "一碗拉面"}
        raise AssertionError(method)

    def subscribe(self, **options):
        self.subscribed.append(options)

    def playback_finished(self, stream_id, played):
        pass


def session():
    sent = []
    return NS(closed=False, sent=sent, message=sent.append, ping_every=5, audio=lambda frame: None)


@pytest.fixture
async def her(tmp_path):
    her = Her(tmp_path / "binding.json", tmp_path / "chat")
    her.client, her.awake = Wife(), True
    yield her


def kinds(sent):
    return [m["type"] for m in sent]


async def settle():
    for _ in range(20):
        await asyncio.sleep(0.01)


async def test_what_he_types_reaches_her_once_and_her_answer_waits_until_his_phone_has_it(her):
    phone, link = Device(b"1" * 8, "iPhone", b"k" * 32, 0), session()
    her.connected(link, phone)
    link.on_message({"type": "hello", "app": "open"})
    said = {"type": "say", "id": "a1", "t": 1, "text": "我到家了"}
    link.on_message(said)
    await settle()
    answer = next(m for m in link.sent if m["type"] == "her")
    assert answer["text"] == "欢迎回来" and "opus" not in answer                 # typed: she types back
    assert {"type": "got", "ids": ["a1"]} in link.sent
    link.on_message(said)                                                       # sent again: only confirmed
    await settle()
    assert [m for m, _ in her.client.asked].count("input") == 1
    her.ended(link, phone)                                                      # gone before it said `got`
    again = session()
    her.connected(again, phone)
    again.on_message({"type": "hello", "app": "open"})
    assert answer in again.sent
    again.on_message({"type": "got", "ids": [answer["id"]]})
    assert her.phones[phone.dev].outbox.waiting() == []


async def test_his_voice_message_is_answered_with_her_voice_but_not_if_it_is_not_his_voice(her):
    phone, link = Device(b"2" * 8, "iPhone", b"k" * 32, 0), session()
    her.connected(link, phone)
    recording = encode_her(np.zeros(16000, dtype=np.int16), 16000)[0]
    link.on_message({"type": "voice", "id": "v1", "t": 1, "ms": 1000, "opus": recording})
    await settle()
    heard = next(m for m in link.sent if m["type"] == "heard")
    assert heard == {"type": "heard", "id": "v1", "text": "我到家了", "ok": True}
    assert ("input", {"text": "我到家了", "input_type": "voice", "present": False, "voice": False, "wait": True}) \
        in her.client.asked
    answers = [m for m in link.sent if m["type"] == "her"]
    assert answers[0]["text"] == "欢迎回来" and answers[-1]["id"] == answers[0]["id"] and answers[-1]["ms"] == 500
    assert ("tts", {"text": "欢迎回来", "emotion": "happy"}) in her.client.asked
    her.client.master = False                                                   # someone else's voice
    link.on_message({"type": "voice", "id": "v2", "t": 2, "ms": 1000, "opus": recording})
    await settle()
    assert {"type": "heard", "id": "v2", "text": "我到家了", "ok": False} in link.sent
    assert {"type": "got", "ids": ["v2"]} in link.sent and [m for m, _ in her.client.asked].count("input") == 1


async def test_a_photo_is_described_to_her(her):
    phone, link = Device(b"3" * 8, "iPhone", b"k" * 32, 0), session()
    her.connected(link, phone)
    link.on_message({"type": "photo", "id": "p1", "t": 1, "jpeg": "AAAA", "text": "我的晚饭"})
    await settle()
    assert her.client.asked[-1][1]["text"] == "（他从手机发来一张照片：一碗拉面）我的晚饭"


async def test_while_he_is_away_what_she_says_goes_to_every_phone_with_her_voice(her):
    here, there = Device(b"4" * 8, "小米", b"k" * 32, 0), Device(b"5" * 8, "iPhone", b"k" * 32, 0)
    link = session()
    her.connected(link, here)
    her._phone(there)                                                            # paired, not connected now
    her._reply({"turn_id": 7, "text": "你在干嘛", "source": "desktop"}, True)
    assert "her" not in kinds(link.sent)                                         # he is at the computer
    her._presence(True)
    await settle()
    assert her.client.subscribed[-1] == {"topics": her._subscription()["topics"], "audio": True, "audio_owner": True,
                                         "context": REACHABLE}
    her._reply({"turn_id": 8, "text": "你去哪了呀", "source": "auto"}, True)
    her._audio_start({"stream_id": 3, "turn_id": 8, "sample_rate": 32000, "channels": 1})
    her._audio_chunk(3, (np.ones(16000, dtype=np.int16) * 100).tobytes())
    her._audio_end(3, True)
    mine = [m for m in link.sent if m["type"] == "her"]
    assert [m["text"] for m in mine] == ["你去哪了呀", "你去哪了呀"] and mine[1]["ms"] == 500
    later = her.phones[there.dev].outbox.waiting()
    assert len(later) == 1 and later[0]["opus"] == mine[1]["opus"]               # for the iPhone when it comes
    link.on_message({"type": "hello", "app": "open"})
    await settle()
    assert her.client.subscribed[-1]["context"] == OUT


async def test_her_answer_as_she_writes_it_goes_to_the_phone_that_asked(her):
    phone, link = Device(b"6" * 8, "iPhone", b"k" * 32, 0), session()
    her.connected(link, phone)
    her._asking = her.phones[phone.dev]
    her._reply({"turn_id": 9, "text": "欢迎", "source": ADDON_ID}, False)
    assert link.sent[-1] == {"type": "reply", "turn": 9, "text": "欢迎", "final": False}
