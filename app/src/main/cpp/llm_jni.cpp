#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "WikiLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlmHandle {
    llama_model*       model = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_context*     ctx   = nullptr;
    llama_sampler*     smpl  = nullptr;
    int   n_ctx = 2048;
    float temp  = 0.7f; // current sampler temperature
};

// Build the sampler chain: penalties → min_p → temp → dist.
static llama_sampler* make_sampler(float temp) {
    if (temp < 0.05f) temp = 0.05f;
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* s = llama_sampler_chain_init(sparams);
    // Penalties first so they reshape the logits before temp/min_p.
    llama_sampler_chain_add(s, llama_sampler_init_penalties(
        /*penalty_last_n*/ 128, /*penalty_repeat*/ 1.15f,
        /*penalty_freq*/   0.0f, /*penalty_present*/ 0.0f));
    llama_sampler_chain_add(s, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return s;
}

// Rebuild the sampler only when the requested temperature changed.
static void ensure_temp(LlmHandle* h, float temp) {
    if (temp < 0.05f) temp = 0.05f;
    if (h->smpl && temp == h->temp) return;
    if (h->smpl) llama_sampler_free(h->smpl);
    h->smpl = make_sampler(temp);
    h->temp = temp;
}

std::once_flag g_backend_once;

// Last error captured from llama_log_set callback, surfaced to Kotlin.
std::mutex      g_err_mu;
std::string     g_last_error;

void llama_log_cb(ggml_log_level level, const char* text, void* /*user*/) {
    if (!text) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", text);
            {
                std::lock_guard<std::mutex> lk(g_err_mu);
                if (!g_last_error.empty()) g_last_error += '\n';
                g_last_error += text;
                // Cap so we don't accumulate forever.
                if (g_last_error.size() > 4096) {
                    g_last_error = g_last_error.substr(g_last_error.size() - 4096);
                }
            }
            break;
        case GGML_LOG_LEVEL_WARN:
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", text);
            break;
        default:
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", text);
            break;
    }
}

void clear_last_error() {
    std::lock_guard<std::mutex> lk(g_err_mu);
    g_last_error.clear();
}

void ensure_backend() {
    std::call_once(g_backend_once, []() {
        llama_log_set(llama_log_cb, nullptr);
        llama_backend_init();
        LOGI("llama_backend_init done");
    });
}

std::vector<llama_token> tokenize_text(const llama_vocab* vocab,
                                       const std::string& text,
                                       bool add_special) {
    int est = static_cast<int>(text.size()) + 8;
    std::vector<llama_token> out(est);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           out.data(), static_cast<int>(out.size()),
                           add_special, /*parse_special*/ true);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           out.data(), static_cast<int>(out.size()),
                           add_special, true);
        if (n < 0) return {};
    }
    out.resize(n);
    return out;
}

std::string token_piece(const llama_vocab* vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special*/ true);
    if (n < 0) return {};
    return std::string(buf, n);
}

static const char* DEFAULT_SYSTEM_PROMPT =
    "Ты — полезный ассистент. Отвечай кратко и по существу на русском языке. "
    "Если ответ короткий — не растягивай. Если не знаешь точно — пиши \"не знаю\" вместо догадок. "
    "Не повторяй одну и ту же мысль или слово несколько раз.";

// Run the model's built-in chat template (string from metadata) on the given
// messages. Returns formatted prompt or empty string on failure.
static std::string run_template(const char* tmpl,
                                std::vector<llama_chat_message>& msgs) {
    if (!tmpl) return {};
    int needed = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                           /*add_ass=*/true, nullptr, 0);
    if (needed <= 0) return {};
    std::vector<char> buf(needed + 1, 0);
    int got = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                        true, buf.data(), static_cast<int>(buf.size()));
    if (got <= 0) return {};
    return std::string(buf.data(), static_cast<size_t>(got));
}

// Single-turn convenience: system + user.
std::string apply_chat_template(const llama_model* model, const std::string& user_text) {
    const char* tmpl = llama_model_chat_template(model, /*name=*/nullptr);

    std::vector<llama_chat_message> with_sys = {
        {"system", DEFAULT_SYSTEM_PROMPT},
        {"user",   user_text.c_str()},
    };
    auto res = run_template(tmpl, with_sys);
    if (!res.empty()) return res;

    // Some templates reject the system role — retry user-only.
    LOGW("chat template w/ system failed, retrying user-only");
    std::vector<llama_chat_message> user_only = { {"user", user_text.c_str()} };
    res = run_template(tmpl, user_only);
    if (!res.empty()) return res;

    LOGW("chat template unavailable, using raw prompt");
    return user_text;
}

// Multi-turn: system + full history, with progressive fallbacks.
// systemPrompt may be empty to omit the system message entirely.
static std::string apply_chat_template_multi(
    const llama_model* model,
    const std::vector<std::pair<std::string, std::string>>& history,
    const std::string& systemPrompt) {

    const char* tmpl = llama_model_chat_template(model, nullptr);

    // With system message (if provided).
    std::vector<llama_chat_message> msgs;
    msgs.reserve(history.size() + 1);
    if (!systemPrompt.empty())
        msgs.push_back({"system", systemPrompt.c_str()});
    for (auto& [role, content] : history)
        msgs.push_back({role.c_str(), content.c_str()});

    auto res = run_template(tmpl, msgs);
    if (!res.empty()) return res;

    // Retry without the system message (some templates reject the system role).
    if (!systemPrompt.empty() && !msgs.empty() && std::string(msgs.front().role) == "system") {
        LOGW("multi-turn template w/ system failed, retrying without");
        msgs.erase(msgs.begin());
        res = run_template(tmpl, msgs);
        if (!res.empty()) return res;
    }

    // Last resort: single-turn for the last user message.
    LOGW("multi-turn template failed, falling back to single-turn");
    for (int i = (int)history.size() - 1; i >= 0; i--) {
        if (history[i].first == "user")
            return apply_chat_template(model, history[i].second);
    }
    return history.empty() ? std::string{} : history.back().second;
}

// Length of the longest prefix of s that ends on a complete UTF-8 character.
// A token piece can split a multi-byte character (common with Cyrillic), so we
// hold back the trailing incomplete bytes until the next token completes them.
static size_t utf8_complete_len(const std::string& s) {
    size_t len = s.size();
    if (len == 0) return 0;
    // Walk back over continuation bytes (10xxxxxx) to the lead byte of the last char.
    size_t j = len - 1;
    while (j > 0 && ((unsigned char)s[j] & 0xC0) == 0x80) j--;
    unsigned char lead = (unsigned char)s[j];
    size_t expected;
    if ((lead & 0x80) == 0x00)      expected = 1;
    else if ((lead & 0xE0) == 0xC0) expected = 2;
    else if ((lead & 0xF0) == 0xE0) expected = 3;
    else if ((lead & 0xF8) == 0xF0) expected = 4;
    else return len; // not a lead byte (malformed) — don't hold back
    size_t avail = len - j;
    return (avail >= expected) ? len : j; // complete → all; incomplete → cut before lead
}

// Qwen3-style "thinking" models otherwise burn the whole token budget on a
// <think>…</think> block and never reach the answer. If the model's template
// supports thinking, pre-close the block so we get a direct answer. Detected
// generically by looking for "think" in the model's chat template.
static void maybe_suppress_thinking(const llama_model* model, std::string& formatted) {
    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl && std::strstr(tmpl, "think")) {
        formatted += "<think>\n\n</think>\n\n";
        LOGI("thinking model detected — pre-closed <think> for a direct answer");
    }
}

// Shared generation loop used by both nativeGenerate and nativeGenerateChat.
// Streams complete UTF-8 chunks as byte[] (Kotlin decodes them), so we never
// hand partial/4-byte sequences to NewStringUTF (which aborts on those).
// Reports prompt/generated token counts via TokenCallback.onComplete at the end.
static void run_generation(
    JNIEnv* env, LlmHandle* h,
    const std::string& formatted,
    int maxTokens,
    jobject callback) {

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod    = env->GetMethodID(cbClass, "onToken",    "([B)Z");
    jmethodID onCompleteMethod = env->GetMethodID(cbClass, "onComplete", "(II)V");
    if (!onTokenMethod) { LOGE("TokenCallback.onToken not found"); return; }

    // Emit a complete-UTF-8 chunk to Kotlin; returns false to stop generation.
    auto emit_chunk = [&](const std::string& chunk) -> bool {
        if (chunk.empty()) return true;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(chunk.size()));
        if (!arr) return true;
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(chunk.size()),
                                reinterpret_cast<const jbyte*>(chunk.data()));
        jboolean keep = env->CallBooleanMethod(callback, onTokenMethod, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return keep;
    };

    auto tokens = tokenize_text(h->vocab, formatted, /*add_special=*/true);
    if (tokens.empty()) { LOGE("Tokenization failed"); return; }
    const int promptTokens = static_cast<int>(tokens.size());
    LOGI("Prompt: %d tokens", promptTokens);

    // Reset the KV cache; positions are tracked automatically by llama_decode
    // when batches come from llama_batch_get_one().
    llama_memory_clear(llama_get_memory(h->ctx), /*data=*/true);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int>(tokens.size()));

    llama_token next_token = 0;
    int generated = 0;
    std::string pending; // buffers bytes until they form a complete UTF-8 char
    while (generated < maxTokens) {
        if (llama_decode(h->ctx, batch) != 0) { LOGE("llama_decode failed"); break; }
        next_token = llama_sampler_sample(h->smpl, h->ctx, -1); // also accepts the token
        if (llama_vocab_is_eog(h->vocab, next_token)) { LOGI("EOG, stopping"); break; }
        ++generated;
        pending += token_piece(h->vocab, next_token);
        size_t cut = utf8_complete_len(pending);
        if (cut > 0) {
            std::string chunk = pending.substr(0, cut);
            pending.erase(0, cut);
            if (!emit_chunk(chunk)) { LOGI("Cancelled"); break; }
        }
        batch = llama_batch_get_one(&next_token, 1);
    }
    // Flush any complete bytes left in the buffer (drop a trailing partial char).
    if (!pending.empty()) {
        size_t cut = utf8_complete_len(pending);
        if (cut > 0) emit_chunk(pending.substr(0, cut));
    }
    LOGI("Done, generated=%d", generated);
    if (onCompleteMethod) {
        env->CallVoidMethod(callback, onCompleteMethod,
                            static_cast<jint>(promptTokens), static_cast<jint>(generated));
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeLoad(
    JNIEnv* env, jclass /*clazz*/, jstring jpath, jint nCtx) {

    ensure_backend();
    clear_last_error();

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;
    std::string path_str(path);
    env->ReleaseStringUTFChars(jpath, path);

    auto* h = new LlmHandle();
    h->n_ctx = nCtx;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = true;

    LOGI("Loading model: %s", path_str.c_str());
    h->model = llama_model_load_from_file(path_str.c_str(), mparams);
    if (!h->model) {
        LOGE("llama_model_load_from_file failed");
        std::lock_guard<std::mutex> lk(g_err_mu);
        if (g_last_error.empty()) g_last_error = "llama_model_load_from_file returned null";
        delete h;
        return 0;
    }
    h->vocab = llama_model_get_vocab(h->model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = nCtx;
    cparams.n_batch = 2048;
    cparams.no_perf = true;

    h->ctx = llama_init_from_model(h->model, cparams);
    if (!h->ctx) {
        LOGE("llama_init_from_model failed");
        std::lock_guard<std::mutex> lk(g_err_mu);
        if (g_last_error.empty()) g_last_error = "llama_init_from_model returned null";
        llama_model_free(h->model);
        delete h;
        return 0;
    }

    h->temp = 0.7f;
    h->smpl = make_sampler(h->temp);

    LOGI("Model loaded OK");
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeLastError(
    JNIEnv* env, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lk(g_err_mu);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeFree(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* h = reinterpret_cast<LlmHandle*>(handle);
    if (!h) return;
    if (h->smpl)  llama_sampler_free(h->smpl);
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeGenerate(
    JNIEnv* env, jclass /*clazz*/, jlong handle,
    jstring jprompt, jint maxTokens, jobject callback) {

    auto* h = reinterpret_cast<LlmHandle*>(handle);
    if (!h || !h->model || !h->ctx) return;

    const char* prompt_chars = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt_chars) return;
    std::string user_text(prompt_chars);
    env->ReleaseStringUTFChars(jprompt, prompt_chars);

    std::string formatted = apply_chat_template(h->model, user_text);
    maybe_suppress_thinking(h->model, formatted);
    LOGI("nativeGenerate: formatted prompt (%zu chars)", formatted.size());
    run_generation(env, h, formatted, maxTokens, callback);
}

// Multi-turn chat: roles[] and contents[] are parallel String arrays.
// systemPrompt, temperature and noThink come from the app's generation settings.
extern "C" JNIEXPORT void JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeGenerateChat(
    JNIEnv* env, jclass /*clazz*/, jlong handle,
    jobjectArray jroles, jobjectArray jcontents,
    jint maxTokens, jstring jSystemPrompt, jfloat temperature, jboolean noThink,
    jobject callback) {

    auto* h = reinterpret_cast<LlmHandle*>(handle);
    if (!h || !h->model || !h->ctx) return;

    std::string systemPrompt;
    if (jSystemPrompt) {
        const char* sp = env->GetStringUTFChars(jSystemPrompt, nullptr);
        if (sp) { systemPrompt = sp; env->ReleaseStringUTFChars(jSystemPrompt, sp); }
    }

    jsize count = env->GetArrayLength(jroles);
    std::vector<std::pair<std::string, std::string>> history(count);
    for (jsize i = 0; i < count; i++) {
        auto jr = (jstring)env->GetObjectArrayElement(jroles, i);
        auto jc = (jstring)env->GetObjectArrayElement(jcontents, i);
        const char* r = jr ? env->GetStringUTFChars(jr, nullptr) : nullptr;
        const char* c = jc ? env->GetStringUTFChars(jc, nullptr) : nullptr;
        if (r) { history[i].first  = r; env->ReleaseStringUTFChars(jr, r); }
        if (c) { history[i].second = c; env->ReleaseStringUTFChars(jc, c); }
        if (jr) env->DeleteLocalRef(jr);
        if (jc) env->DeleteLocalRef(jc);
    }

    ensure_temp(h, temperature);

    std::string formatted = apply_chat_template_multi(h->model, history, systemPrompt);
    if (noThink) maybe_suppress_thinking(h->model, formatted);
    LOGI("nativeGenerateChat: %zu turns, temp=%.2f, noThink=%d, formatted (%zu chars)",
         (size_t)count, h->temp, (int)noThink, formatted.size());
    run_generation(env, h, formatted, maxTokens, callback);
}
