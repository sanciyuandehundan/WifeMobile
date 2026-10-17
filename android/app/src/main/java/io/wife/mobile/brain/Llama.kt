package io.wife.mobile.brain

/** llama.cpp (app/src/main/cpp/wife.cpp): the computer's brain model and chat LoRA, run on the phone. */
object Llama {
    init { System.loadLibrary("wife") }

    fun interface Piece {
        /** Whole UTF-8 characters as they come; false stops. */
        fun take(bytes: ByteArray): Boolean
    }

    @JvmStatic external fun load(path: String, context: Int, threads: Int): Long
    @JvmStatic external fun adapter(handle: Long, path: String): Boolean
    /** Reads a prompt ahead of time, so the next answer only reads what was added. */
    @JvmStatic external fun prefill(handle: Long, prompt: String): String
    @JvmStatic external fun generate(handle: Long, prompt: String, maxTokens: Int, piece: Piece): String
    @JvmStatic external fun free(handle: Long)
}
