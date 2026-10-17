package io.wife.mobile.voice

/** libopus (app/src/main/cpp/wife.cpp). */
object Opus {
    init { System.loadLibrary("wife") }

    @JvmStatic external fun encoder(rate: Int, bitrate: Int, voice: Boolean): Long
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray): ByteArray?
    @JvmStatic external fun decoder(rate: Int): Long
    /** ``packet`` null: a lost frame, filled in by the decoder. */
    @JvmStatic external fun decode(handle: Long, packet: ByteArray?, samples: Int): ShortArray
    @JvmStatic external fun free(handle: Long, encoder: Boolean)
}
