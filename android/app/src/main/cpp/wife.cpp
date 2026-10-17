// The app's native side: Opus for calls, llama.cpp for her brain on the phone (the same model and chat LoRA as on
// the computer, sampled the same way: mind/brain.py's settings and llama-server's defaults).
#include <jni.h>
#include <android/log.h>
#include <opus.h>
#include <llama.h>

#include <chrono>
#include <string>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "wife", __VA_ARGS__)

// ── Opus ──

extern "C" JNIEXPORT jlong JNICALL
Java_io_wife_mobile_voice_Opus_encoder(JNIEnv *, jclass, jint rate, jint bitrate, jboolean voice) {
    int error = 0;
    OpusEncoder *encoder = opus_encoder_create(rate, 1, voice ? OPUS_APPLICATION_VOIP : OPUS_APPLICATION_AUDIO,
                                               &error);
    if (error != OPUS_OK) return 0;
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(10));
    return reinterpret_cast<jlong>(encoder);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_wife_mobile_voice_Opus_encode(JNIEnv *env, jclass, jlong handle, jshortArray pcm) {
    jsize n = env->GetArrayLength(pcm);
    jshort *samples = env->GetShortArrayElements(pcm, nullptr);
    unsigned char out[1500];
    opus_int32 size = opus_encode(reinterpret_cast<OpusEncoder *>(handle), samples, n, out, sizeof out);
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);
    if (size < 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<jbyte *>(out));
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_wife_mobile_voice_Opus_decoder(JNIEnv *, jclass, jint rate) {
    int error = 0;
    OpusDecoder *decoder = opus_decoder_create(rate, 1, &error);
    return error == OPUS_OK ? reinterpret_cast<jlong>(decoder) : 0;
}

// A lost frame (packet == null) is filled in by the decoder.
extern "C" JNIEXPORT jshortArray JNICALL
Java_io_wife_mobile_voice_Opus_decode(JNIEnv *env, jclass, jlong handle, jbyteArray packet, jint samples) {
    std::vector<opus_int16> pcm(samples);
    jbyte *data = packet ? env->GetByteArrayElements(packet, nullptr) : nullptr;
    int n = opus_decode(reinterpret_cast<OpusDecoder *>(handle), reinterpret_cast<unsigned char *>(data),
                        packet ? env->GetArrayLength(packet) : 0, pcm.data(), samples, 0);
    if (data) env->ReleaseByteArrayElements(packet, data, JNI_ABORT);
    if (n < 0) n = 0;
    jshortArray result = env->NewShortArray(n);
    env->SetShortArrayRegion(result, 0, n, pcm.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_wife_mobile_voice_Opus_free(JNIEnv *, jclass, jlong handle, jboolean encoder) {
    if (encoder) opus_encoder_destroy(reinterpret_cast<OpusEncoder *>(handle));
    else opus_decoder_destroy(reinterpret_cast<OpusDecoder *>(handle));
}

// ── llama.cpp ──

struct Brain {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::vector<llama_token> cached;     // what the context holds, so the next prompt only reads what is new
};

static Brain *brain(jlong handle) { return reinterpret_cast<Brain *>(handle); }

static double ms_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_wife_mobile_brain_Llama_load(JNIEnv *env, jclass, jstring path, jint context, jint threads) {
    llama_backend_init();
    const char *file = env->GetStringUTFChars(path, nullptr);
    llama_model_params model_params = llama_model_default_params();
    // Keep the weights in the mapped file: repacking them copied all 4.7 GB into the heap, the phone swapped and
    // reading her card ran at 11 tokens a second (Snapdragon 8 Elite, 2026-10-17).
    model_params.use_extra_bufts = false;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model *model = llama_model_load_from_file(file, model_params);
    env->ReleaseStringUTFChars(path, file);
    if (!model) return 0;
    llama_context_params params = llama_context_default_params();
    params.n_ctx = context;
    params.n_batch = params.n_ubatch = 512;
    params.n_threads = params.n_threads_batch = threads;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;      // the computer's settings: q8_0 cache needs it
    params.type_k = params.type_v = GGML_TYPE_Q8_0;
    llama_context *ctx = llama_init_from_model(model, params);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    static const char *breakers[] = {"\n", ":", "\"", "*"};
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.0f, 0.0f, 1.5f));
    llama_sampler_chain_add(chain, llama_sampler_init_dry(vocab, 0.8f, 1.75f, 2, 4096, breakers, 4));
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return reinterpret_cast<jlong>(new Brain{model, ctx, chain});
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_wife_mobile_brain_Llama_adapter(JNIEnv *env, jclass, jlong handle, jstring path) {
    Brain *b = brain(handle);
    const char *file = env->GetStringUTFChars(path, nullptr);
    llama_adapter_lora *lora = llama_adapter_lora_init(b->model, file);
    env->ReleaseStringUTFChars(path, file);
    if (!lora) return JNI_FALSE;
    float scale = 1.0f;
    llama_set_adapters_lora(b->ctx, &lora, 1, &scale);
    llama_memory_clear(llama_get_memory(b->ctx), true);        // what was read without the adapter no longer fits
    b->cached.clear();
    return JNI_TRUE;
}

// Reads the prompt into the context, reusing the part it shares with the last one; returns how many tokens were new.
static int read_prompt(Brain *b, const std::string &prompt) {
    const llama_vocab *vocab = llama_model_get_vocab(b->model);
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), false, true);
    if (n < 0) return -1;
    tokens.resize(n);
    size_t keep = 0;
    while (keep < b->cached.size() && keep < tokens.size() && b->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size() && keep > 0) keep--;                // the last token must be read again for its logits
    llama_memory_seq_rm(llama_get_memory(b->ctx), 0, keep, -1);
    b->cached.resize(keep);
    const size_t batch = llama_n_batch(b->ctx);
    for (size_t i = keep; i < tokens.size(); i += batch) {
        int count = std::min(batch, tokens.size() - i);
        if (llama_decode(b->ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) return -1;
        b->cached.insert(b->cached.end(), tokens.begin() + i, tokens.begin() + i + count);
    }
    return tokens.size() - keep;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_wife_mobile_brain_Llama_prefill(JNIEnv *env, jclass, jlong handle, jstring prompt) {
    const char *text = env->GetStringUTFChars(prompt, nullptr);
    std::string whole(text);
    env->ReleaseStringUTFChars(prompt, text);
    auto start = std::chrono::steady_clock::now();
    int fresh = read_prompt(brain(handle), whole);
    std::string stats = "读了 " + std::to_string(fresh) + " 个 token，" + std::to_string((int) ms_since(start)) + " ms";
    return env->NewStringUTF(stats.c_str());
}

// Bytes up to the last whole UTF-8 character: one Chinese character often spans two tokens.
static size_t whole_utf8(const std::string &s) {
    size_t i = s.size();
    for (size_t back = 1; back <= 4 && back <= s.size(); back++) {
        unsigned char c = s[s.size() - back];
        if ((c & 0xC0) != 0x80) {                                 // the start of the last character
            size_t need = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : 4;
            i = back >= need ? s.size() : s.size() - back;
            break;
        }
    }
    return i;
}

// Generates after the prompt; each whole piece of text goes to piece.take(byte[]), which returns false to stop.
extern "C" JNIEXPORT jstring JNICALL
Java_io_wife_mobile_brain_Llama_generate(JNIEnv *env, jclass, jlong handle, jstring prompt, jint max_tokens,
                                         jobject piece) {
    Brain *b = brain(handle);
    const char *text = env->GetStringUTFChars(prompt, nullptr);
    std::string whole(text);
    env->ReleaseStringUTFChars(prompt, text);
    jmethodID take = env->GetMethodID(env->GetObjectClass(piece), "take", "([B)Z");
    auto start = std::chrono::steady_clock::now();
    int fresh = read_prompt(b, whole);
    double read_ms = ms_since(start);
    if (fresh < 0) return env->NewStringUTF("读输入失败");
    const llama_vocab *vocab = llama_model_get_vocab(b->model);
    llama_sampler_reset(b->sampler);
    std::string pending;
    int made = 0;
    auto first = std::chrono::steady_clock::now();
    for (; made < max_tokens; made++) {
        llama_token token = llama_sampler_sample(b->sampler, b->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof buf, 0, false);
        if (n > 0) pending.append(buf, n);
        size_t ready = whole_utf8(pending);
        bool go_on = true;
        if (ready > 0) {
            jbyteArray bytes = env->NewByteArray(ready);
            env->SetByteArrayRegion(bytes, 0, ready, reinterpret_cast<const jbyte *>(pending.data()));
            go_on = env->CallBooleanMethod(piece, take, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ready);
        }
        if (!go_on) break;
        if (llama_decode(b->ctx, llama_batch_get_one(&token, 1)) != 0) break;
        b->cached.push_back(token);
    }
    double gen_ms = ms_since(first);
    std::string stats = "读了 " + std::to_string(fresh) + " 个 token 用 " + std::to_string((int) read_ms) +
                        " ms，写了 " + std::to_string(made) + " 个用 " + std::to_string((int) gen_ms) + " ms";
    LOG("%s", stats.c_str());
    return env->NewStringUTF(stats.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_wife_mobile_brain_Llama_free(JNIEnv *, jclass, jlong handle) {
    Brain *b = brain(handle);
    llama_sampler_free(b->sampler);
    llama_free(b->ctx);
    llama_model_free(b->model);
    delete b;
}
