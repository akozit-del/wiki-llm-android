#include <jni.h>
#include <android/log.h>
#include <atomic>
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
    int n_ctx = 2048;
};

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
static std::string apply_chat_template_multi(
    const llama_model* model,
    const std::vector<std::pair<std::string, std::string>>& history) {

    const char* tmpl = llama_model_chat_template(model, nullptr);

    // With system message.
    std::vector<llama_chat_message> msgs;
    msgs.reserve(history.size() + 1);
    msgs.push_back({"system", DEFAULT_SYSTEM_PROMPT});
    for (auto& [role, content] : history)
        msgs.push_back({role.c_str(), content.c_str()});

    auto res = run_template(tmpl, msgs);
    if (!res.empty()) return res;

    // Retry without system.
    LOGW("multi-turn template w/ system failed, retrying without");
    msgs.erase(msgs.begin());
    res = run_template(tmpl, msgs);
    if (!res.empty()) return res;

    // Last resort: single-turn for the last user message.
    LOGW("multi-turn template failed, falling back to single-turn");
    for (int i = (int)history.size() - 1; i >= 0; i--) {
        if (history[i].first == "user")
            return apply_chat_template(model, history[i].second);
    }
    return history.empty() ? std::string{} : history.back().second;
}

// Shared generation loop used by both nativeGenerate and nativeGenerateChat.
// Reports prompt/generated token counts via TokenCallback.onComplete at the end.
static void run_generation(
    JNIEnv* env, LlmHandle* h,
    const std::string& formatted,
    int maxTokens,
    jobject callback) {

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod    = env->GetMethodID(cbClass, "onToken",    "(Ljava/lang/String;)Z");
    jmethodID onCompleteMethod = env->GetMethodID(cbClass, "onComplete", "(II)V");
    if (!onTokenMethod) { LOGE("TokenCallback.onToken not found"); return; }

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
    while (generated < maxTokens) {
        if (llama_decode(h->ctx, batch) != 0) { LOGE("llama_decode failed"); break; }
        next_token = llama_sampler_sample(h->smpl, h->ctx, -1); // also accepts the token
        if (llama_vocab_is_eog(h->vocab, next_token)) { LOGI("EOG, stopping"); break; }
        std::string piece = token_piece(h->vocab, next_token);
        ++generated;
        if (!piece.empty()) {
            jstring jpiece = env->NewStringUTF(piece.c_str());
            jboolean keep = env->CallBooleanMethod(callback, onTokenMethod, jpiece);
            env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (!keep) { LOGI("Cancelled"); break; }
        }
        batch = llama_batch_get_one(&next_token, 1);
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

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    h->smpl = llama_sampler_chain_init(sparams);
    // Penalties first so they reshape the logits before temp/min_p.
    // penalty_last_n=128, penalty_repeat=1.15, no freq/presence penalties.
    llama_sampler_chain_add(h->smpl, llama_sampler_init_penalties(
        /*penalty_last_n*/ 128,
        /*penalty_repeat*/ 1.15f,
        /*penalty_freq*/   0.0f,
        /*penalty_present*/ 0.0f));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

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
    LOGI("nativeGenerate: formatted prompt (%zu chars)", formatted.size());
    run_generation(env, h, formatted, maxTokens, callback);
}

// Multi-turn chat: roles[] and contents[] are parallel String arrays.
extern "C" JNIEXPORT void JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeGenerateChat(
    JNIEnv* env, jclass /*clazz*/, jlong handle,
    jobjectArray jroles, jobjectArray jcontents,
    jint maxTokens, jobject callback) {

    auto* h = reinterpret_cast<LlmHandle*>(handle);
    if (!h || !h->model || !h->ctx) return;

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

    std::string formatted = apply_chat_template_multi(h->model, history);
    LOGI("nativeGenerateChat: %zu turns, formatted (%zu chars)", (size_t)count, formatted.size());
    run_generation(env, h, formatted, maxTokens, callback);
}
