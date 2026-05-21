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
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    llama_sampler* smpl  = nullptr;
    int n_ctx = 2048;
};

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
        LOGI("llama_backend_init done");
    });
}

std::vector<llama_token> tokenize_text(const llama_model* model,
                                       const std::string& text,
                                       bool add_special) {
    int est = static_cast<int>(text.size()) + 8;
    std::vector<llama_token> out(est);
    int n = llama_tokenize(model, text.c_str(), static_cast<int>(text.size()),
                           out.data(), static_cast<int>(out.size()),
                           add_special, /*parse_special*/ true);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(model, text.c_str(), static_cast<int>(text.size()),
                           out.data(), static_cast<int>(out.size()),
                           add_special, true);
        if (n < 0) return {};
    }
    out.resize(n);
    return out;
}

std::string token_piece(const llama_model* model, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(model, tok, buf, sizeof(buf), 0, /*special*/ true);
    if (n < 0) return {};
    return std::string(buf, n);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeLoad(
    JNIEnv* env, jclass /*clazz*/, jstring jpath, jint nCtx) {

    ensure_backend();

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
    h->model = llama_load_model_from_file(path_str.c_str(), mparams);
    if (!h->model) {
        LOGE("llama_load_model_from_file failed");
        delete h;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = nCtx;
    cparams.n_batch = 256;
    cparams.no_perf = true;

    h->ctx = llama_new_context_with_model(h->model, cparams);
    if (!h->ctx) {
        LOGE("llama_new_context_with_model failed");
        llama_free_model(h->model);
        delete h;
        return 0;
    }

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    h->smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(h->smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Model loaded OK");
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeFree(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* h = reinterpret_cast<LlmHandle*>(handle);
    if (!h) return;
    if (h->smpl)  llama_sampler_free(h->smpl);
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_free_model(h->model);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wikillm_android_llm_LlamaContext_nativeGenerate(
    JNIEnv* env, jclass /*clazz*/, jlong handle,
    jstring jprompt, jint maxTokens, jobject callback) {

    auto* h = reinterpret_cast<LlmHandle*>(handle);
    if (!h || !h->model || !h->ctx) return;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    if (!onTokenMethod) {
        LOGE("TokenCallback.onToken(String):Z not found");
        return;
    }

    const char* prompt_chars = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt_chars) return;
    std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(jprompt, prompt_chars);

    auto tokens = tokenize_text(h->model, prompt, true);
    if (tokens.empty()) {
        LOGE("Tokenization failed");
        return;
    }
    LOGI("Prompt: %zu tokens", tokens.size());

    llama_kv_cache_clear(h->ctx);

    // In llama.cpp tag b3789, llama_batch_get_one takes (tokens, n_tokens, pos_0, seq_id)
    // so we track the absolute position in the sequence ourselves.
    llama_pos n_past = 0;
    const llama_seq_id seq_id = 0;

    llama_batch batch = llama_batch_get_one(
        tokens.data(), static_cast<int>(tokens.size()), n_past, seq_id);
    n_past += static_cast<llama_pos>(tokens.size());

    llama_token next_token = 0;
    int generated = 0;
    while (generated < maxTokens) {
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("llama_decode failed");
            break;
        }

        next_token = llama_sampler_sample(h->smpl, h->ctx, -1);
        llama_sampler_accept(h->smpl, next_token);

        if (llama_token_is_eog(h->model, next_token)) {
            LOGI("EOG, stopping");
            break;
        }

        std::string piece = token_piece(h->model, next_token);
        if (!piece.empty()) {
            jstring jpiece = env->NewStringUTF(piece.c_str());
            jboolean keep = env->CallBooleanMethod(callback, onTokenMethod, jpiece);
            env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            if (!keep) {
                LOGI("Cancelled by callback");
                break;
            }
        }

        ++generated;
        batch = llama_batch_get_one(&next_token, 1, n_past, seq_id);
        n_past += 1;
    }

    LOGI("Done, generated=%d", generated);
}
