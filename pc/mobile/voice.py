"""Opus both ways: his phone's microphone into 16 kHz PCM, her speech out as real-time 20 ms frames, and whole
recordings in a message (protocol.md: each frame after its 2-byte length, all of it b64)."""
from __future__ import annotations

import asyncio
import io
import time
import wave

import av
import numpy as np
import soxr

from .crypto import b64, unb64

RATE, FRAME = 48000, 960                 # her voice: 48 kHz, 20 ms frames
MIC_RATE, MIC_FRAME_BYTES = 16000, 640   # his microphone: 16 kHz, 20 ms of 16-bit samples
TAIL = 0.2                               # the phone plays this much behind what we send


def pack(frames: list[bytes]) -> str:
    return b64(b"".join(len(frame).to_bytes(2, "big") + frame for frame in frames))


def unpack(text: str) -> list[bytes]:
    data, frames, at = unb64(text), [], 0
    while at + 2 <= len(data):
        size = int.from_bytes(data[at:at + 2], "big")
        frames.append(data[at + 2:at + 2 + size])
        at += 2 + size
    return frames


def wav(pcm: bytes, rate: int = MIC_RATE) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(pcm)
    return buffer.getvalue()


def his_recording(opus: str) -> bytes:
    """His voice message (or a recording for his voiceprint) as 16 kHz PCM."""
    mic = MicIn()
    return b"".join(mic.frame(seq, frame) for seq, frame in enumerate(unpack(opus)))


def her_recording(audio: bytes) -> tuple[str, int]:
    """Her voice (a WAV from Wife) as a message's recording: (opus, ms)."""
    with wave.open(io.BytesIO(audio)) as clip:
        rate, channels = clip.getframerate(), clip.getnchannels()
        samples = np.frombuffer(clip.readframes(clip.getnframes()), dtype=np.int16)
    return encode_her(samples, rate, channels)


def encode_her(samples: np.ndarray, rate: int, channels: int = 1) -> tuple[str, int]:
    if channels > 1:
        samples = samples[:len(samples) // channels * channels].reshape(-1, channels).mean(axis=1).astype(np.int16)
    samples = soxr.resample(samples, rate, RATE).astype(np.int16)
    encoder = av.CodecContext.create("libopus", "w")
    encoder.sample_rate, encoder.layout, encoder.format, encoder.bit_rate = RATE, "mono", "s16", 32000
    frames = []
    for at in range(0, len(samples), FRAME):
        chunk = np.zeros(FRAME, dtype=np.int16)
        chunk[:len(samples[at:at + FRAME])] = samples[at:at + FRAME]
        frame = av.AudioFrame.from_ndarray(chunk.reshape(1, -1), format="s16", layout="mono")
        frame.sample_rate, frame.pts = RATE, at
        frames += [bytes(packet) for packet in encoder.encode(frame)]
    frames += [bytes(packet) for packet in encoder.encode(None)]
    return pack(frames), len(samples) * 1000 // RATE


class MicIn:
    """Decodes the phone's frames. A few lost frames become silence: the recognizer only needs the words."""

    def __init__(self) -> None:
        self._decoder = av.CodecContext.create("libopus", "r")
        self._decoder.sample_rate, self._decoder.layout = 48000, "mono"
        self._resampler = av.AudioResampler(format="s16", layout="mono", rate=MIC_RATE)
        self._next: int | None = None

    def frame(self, seq: int, data: bytes) -> bytes:
        silence = 0
        if self._next is not None:
            gap = (seq - self._next) & 0xFFFF
            if gap >= 0x8000:                 # late or repeated
                return b""
            silence = min(gap, 5)
        self._next = (seq + 1) & 0xFFFF
        out = bytearray(MIC_FRAME_BYTES * silence)
        for decoded in self._decoder.decode(av.Packet(data)):
            for frame in self._resampler.resample(decoded):
                out += frame.to_ndarray().tobytes()
        return bytes(out)


class VoiceOut:
    """Her PCM from Wife (any rate) → 48 kHz mono → Opus, paced in real time. ``played(stream_id)`` fires when the
    phone should have finished playing a stream."""

    def __init__(self, send_frame, played) -> None:
        self._send, self._played = send_frame, played
        self._encoder = av.CodecContext.create("libopus", "w")
        self._encoder.sample_rate, self._encoder.layout, self._encoder.format = RATE, "mono", "s16"
        self._encoder.bit_rate = 32000
        self._pts = 0
        self._pcm = bytearray()
        self._streams: dict[int, tuple[soxr.ResampleStream, int]] = {}
        self._ends: list[tuple[int, int]] = []        # (byte position in everything queued, stream id)
        self._queued = self._sent = 0
        self.pending: set[int] = set()

    def start(self, stream_id: int, rate: int, channels: int = 1) -> None:
        self._streams[stream_id] = (soxr.ResampleStream(rate, RATE, 1, dtype="int16"), channels)
        self.pending.add(stream_id)

    def chunk(self, stream_id: int, data: bytes) -> None:
        entry = self._streams.get(stream_id)
        if entry is None:
            return
        samples = np.frombuffer(data[:len(data) // 2 * 2], dtype=np.int16)
        if entry[1] > 1:
            samples = samples[:len(samples) // entry[1] * entry[1]].reshape(-1, entry[1]).mean(axis=1).astype(np.int16)
        self._queue(entry[0].resample_chunk(samples).tobytes())

    def end(self, stream_id: int) -> None:
        entry = self._streams.pop(stream_id, None)
        if entry is None:
            return
        self._queue(entry[0].resample_chunk(np.zeros(0, dtype=np.int16), last=True).tobytes())
        self._queue(bytes(2 * FRAME))                   # 20 ms of quiet pushes the last frame out of the encoder
        self._ends.append((self._queued, stream_id))

    def cancel(self, stream_id: int) -> None:
        """Wife stopped her (he interrupted): drop what hasn't gone out yet."""
        self._streams.pop(stream_id, None)
        self.pending.discard(stream_id)
        self._pcm.clear()
        self._queued = self._sent
        self._ends = [(self._sent, sid) for _, sid in self._ends if sid != stream_id]

    def _queue(self, pcm: bytes) -> None:
        self._pcm += pcm
        self._queued += len(pcm)

    def _encode(self, pcm: bytes) -> None:
        frame = av.AudioFrame.from_ndarray(np.frombuffer(pcm, dtype=np.int16).reshape(1, -1), format="s16",
                                           layout="mono")
        frame.sample_rate, frame.pts = RATE, self._pts
        self._pts += FRAME
        for packet in self._encoder.encode(frame):
            self._send(bytes(packet))

    async def run(self) -> None:
        clock = time.monotonic()
        while True:
            whole = len(self._pcm) >= 2 * FRAME
            if whole or (self._pcm and not self._streams):
                pcm = bytes(self._pcm[:2 * FRAME])
                del self._pcm[:2 * FRAME]
                self._sent += len(pcm)
                self._encode(pcm.ljust(2 * FRAME, b"\0"))
                while self._ends and self._ends[0][0] <= self._sent:
                    sid = self._ends.pop(0)[1]
                    asyncio.get_running_loop().call_later(TAIL, self._finished, sid)
                clock = max(clock + FRAME / RATE, time.monotonic() - 0.06)   # catch up at most 60 ms
                await asyncio.sleep(max(0.0, clock - time.monotonic()))
            else:
                clock = time.monotonic()
                await asyncio.sleep(0.005)

    def _finished(self, stream_id: int) -> None:
        if stream_id in self.pending:
            self.pending.discard(stream_id)
            self._played(stream_id)
