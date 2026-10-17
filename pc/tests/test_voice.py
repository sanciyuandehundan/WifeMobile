"""Opus both ways without a phone: her speech is paced in real time and reported played; his frames decode."""
import asyncio
import time

import av
import numpy as np

from mobile.voice import FRAME, MIC_FRAME_BYTES, MicIn, VoiceOut


def tone(rate, seconds):
    t = np.arange(int(rate * seconds)) / rate
    return (np.sin(2 * np.pi * 440 * t) * 8000).astype(np.int16).tobytes()


async def test_her_speech_goes_out_in_real_time_and_is_reported_played():
    frames, played = [], []
    out = VoiceOut(lambda frame: frames.append((time.monotonic(), frame)), played.append)
    runner = asyncio.create_task(out.run())
    out.start(7, 32000)
    pcm = tone(32000, 0.5)
    for i in range(0, len(pcm), 3200):                   # Wife sends 50 ms pieces
        out.chunk(7, pcm[i:i + 3200])
    out.end(7)
    await asyncio.sleep(1.0)
    runner.cancel()
    spread = frames[-1][0] - frames[0][0]
    assert 25 <= len(frames) <= 28 and 0.45 <= spread <= 0.6         # ~0.52 s of audio, not sent all at once
    assert played == [7] and not out.pending
    decoder = av.CodecContext.create("libopus", "r")
    decoder.sample_rate, decoder.layout = 48000, "mono"
    samples = sum(f.samples for _, data in frames for f in decoder.decode(av.Packet(data)))
    assert samples >= 0.5 * 48000


async def test_an_interrupted_sentence_stops_and_is_not_reported():
    frames, played = [], []
    out = VoiceOut(frames.append, played.append)
    runner = asyncio.create_task(out.run())
    out.start(1, 24000)
    out.chunk(1, tone(24000, 2))
    await asyncio.sleep(0.2)
    out.cancel(1)
    sent = len(frames)
    await asyncio.sleep(0.3)
    runner.cancel()
    assert len(frames) <= sent + 1 and played == []


def test_his_frames_decode_to_16_khz_and_lost_ones_become_silence():
    encoder = av.CodecContext.create("libopus", "w")
    encoder.sample_rate, encoder.layout, encoder.format, encoder.bit_rate = 16000, "mono", "s16", 24000
    pcm = np.frombuffer(tone(16000, 0.2), dtype=np.int16)
    packets = []
    for i in range(0, len(pcm), 320):
        frame = av.AudioFrame.from_ndarray(pcm[i:i + 320].reshape(1, -1), format="s16", layout="mono")
        frame.sample_rate, frame.pts = 16000, i
        packets += [bytes(p) for p in encoder.encode(frame)]
    mic = MicIn()
    heard = [mic.frame(seq, data) for seq, data in enumerate(packets[:4])]
    heard.append(mic.frame(6, packets[6]))               # frames 4 and 5 were lost on the way
    assert heard[-1][:2 * MIC_FRAME_BYTES] == bytes(2 * MIC_FRAME_BYTES)
    assert mic.frame(2, packets[2]) == b""               # a late frame is dropped
    assert sum(map(len, heard)) >= 5 * MIC_FRAME_BYTES
