"""Pairing and calls between the computer's Link and a phone written in Python, over loopback UDP."""
import asyncio

import pytest

from mobile.crypto import b64
from mobile.link import Link
from mobile.store import Store
from tests.phone import Phone


@pytest.fixture
async def link(tmp_path):
    link = Link(Store(tmp_path), port=0)
    await link.start()
    yield link
    link.stop()


def at(link):
    return ("127.0.0.1", link.port)


async def test_a_pairing_code_pairs_one_phone_once_and_is_never_written_down(link, tmp_path):
    qr = link.begin_pairing()
    secret = qr.split("&s=")[1].split("&")[0]
    paired = await (await Phone().open()).pair(qr, at(link))
    assert paired["type"] == "paired" and paired["find"] == "ipv6"
    with pytest.raises(asyncio.TimeoutError):            # the same code, a second phone: burned
        await (await Phone().open()).pair(qr, at(link), name="别人的手机")
    assert [d.name for d in Store(tmp_path).devices.values()] == ["测试手机"]
    device = next(iter(link.store.devices.values()))
    saved = b"".join(f.read_bytes() for f in tmp_path.iterdir())
    assert secret.encode() not in saved and b64(device.key).encode() not in saved and device.key not in saved
    assert Store(tmp_path).devices[device.dev].key == device.key          # DPAPI gives it back


async def test_a_lost_pairing_reply_is_sent_again(link):
    phone = await Phone().open()
    await phone.pair(link.begin_pairing(), at(link))
    phone.send(phone.pair_request, at(link))             # his phone didn't hear us and asks again
    data, _ = await phone.reply()
    assert data == phone.pair_reply and len(link.store.devices) == 1


async def test_a_session_carries_his_words_and_voice_and_her_reply(link):
    phone = await Phone().open()
    await phone.pair(link.begin_pairing(), at(link))
    got = {"messages": [], "audio": []}

    def on_session(session, device):
        session.on_message = got["messages"].append
        session.on_audio = lambda seq, frame: got["audio"].append((seq, frame))
        got["session"] = session

    link.on_session = on_session
    answer = await phone.dial(at(link), ping=45)
    assert answer["type"] == "answer" and got["session"].ping_every == 45 and got["session"].dead_after == 180
    sent = []
    phone.session._send = lambda data, address: (sent.append(data), phone.send(data, address))
    phone.session.message({"type": "say", "id": "a", "text": "你好"})
    phone.session.audio(b"opus-frame")
    await asyncio.sleep(0.2)
    assert got["messages"] == [{"type": "say", "id": "a", "text": "你好"}] and got["audio"] == [(1, b"opus-frame")]
    for packet in sent:                                   # someone replays what he sent: ignored
        phone.send(packet, at(link))
    await asyncio.sleep(0.2)
    assert len(got["messages"]) == 1 and len(got["audio"]) == 1
    got["session"].message({"type": "reply", "turn": 3, "text": "欢迎回来", "final": True})
    assert (await asyncio.wait_for(phone.messages.get(), 2))["text"] == "欢迎回来"
    card = {"type": "card", "card": "【她】" + "她记得的事。" * 800}         # ~14 KB: sent in 1000-byte pieces
    got["session"].message(card)
    assert await asyncio.wait_for(phone.messages.get(), 2) == card


async def test_a_photo_goes_out_32_pieces_at_a_time(link):
    phone = await Phone().open()
    await phone.pair(link.begin_pairing(), at(link))
    sessions = []
    link.on_session = lambda session, device: sessions.append(session)
    await phone.dial(at(link))
    photo = {"type": "photo", "id": "p", "jpeg": "x" * 300_000}            # 300 pieces
    sessions[0].message(photo)
    assert len(sessions[0]._unacked) == 32 and len(sessions[0]._waiting) == 300 - 32 + 1
    assert await asyncio.wait_for(phone.messages.get(), 10) == photo
    assert not sessions[0]._unacked and not sessions[0]._waiting


async def test_a_redialled_offer_gets_the_same_answer_and_a_forgotten_phone_gets_none(link):
    phone = await Phone().open()
    await phone.pair(link.begin_pairing(), at(link))
    first = await phone.dial(at(link))
    phone.send(phone.offer, at(link))                     # our answer was lost; he sends the offer again
    data, _ = await phone.reply()
    assert phone.open_reply(data)["e"] == first["e"]
    link.store.forget(phone.dev)
    with pytest.raises(asyncio.TimeoutError):
        await phone.dial(at(link))


async def test_at_home_the_phone_asks_where_the_computer_is(link):
    phone = await Phone().open()
    await phone.pair(link.begin_pairing(), at(link))
    phone.send(phone.sealed({"type": "where"}), at(link))
    data, _ = await phone.reply()
    here = phone.open_reply(data)
    assert here["type"] == "here" and here["find"] == "ipv6" and "nodes" not in here
