#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "HarnessNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// -----------------------------------------------------------------------------
// Global state — single model, single context.
// -----------------------------------------------------------------------------
static struct {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    int                n_ctx = 2048;
    int                n_threads = 4;
} g_state;

static volatile bool g_abort = false;

// -----------------------------------------------------------------------------
// Internal: create a fresh context. Called before each generation because the
// harness resends the full prompt every turn — the old KV cache must be cleared.
// -----------------------------------------------------------------------------
static bool recreateContext() {
    if (g_state.ctx) { llama_free(g_state.ctx); g_state.ctx = nullptr; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = g_state.n_ctx;
    cparams.n_batch         = 512;
    cparams.n_threads       = g_state.n_threads;
    cparams.n_threads_batch = g_state.n_threads;

    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    return g_state.ctx != nullptr;
}

// -----------------------------------------------------------------------------
// hello — smoke test.
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_roleplay_harness_NativeLib_hello(JNIEnv* env, jobject /* this */) {
    LOGI("native-lib loaded and hello() called");
    return env->NewStringUTF("native lib is alive");
}

// -----------------------------------------------------------------------------
// loadModel — loads a GGUF and creates the inference context.
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_roleplay_harness_NativeLib_loadModel(
        JNIEnv* env, jobject /* this */,
        jstring jpath, jint n_ctx, jint n_threads)
{
    if (g_state.ctx)   { llama_free(g_state.ctx);         g_state.ctx = nullptr; }
    if (g_state.model) { llama_model_free(g_state.model); g_state.model = nullptr; }
    g_state.vocab = nullptr;

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("loadModel: path=%s n_ctx=%d n_threads=%d", path, n_ctx, n_threads);

    llama_model_params mparams = llama_model_default_params();
    // mmap is default-on in this version of llama.cpp.
    mparams.n_gpu_layers = 0;  // CPU only; Adreno 619 Vulkan is unreliable for LLM

    g_state.model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_state.model) {
        LOGE("loadModel: llama_model_load_from_file returned null");
        return JNI_FALSE;
    }

    g_state.vocab     = llama_model_get_vocab(g_state.model);
    g_state.n_ctx     = n_ctx;
    g_state.n_threads = n_threads;

    if (!recreateContext()) {
        LOGE("loadModel: llama_init_from_model returned null");
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        return JNI_FALSE;
    }

    LOGI("loadModel: success");
    return JNI_TRUE;
}

// -----------------------------------------------------------------------------
// freeModel.
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_roleplay_harness_NativeLib_freeModel(JNIEnv*, jobject) {
    if (g_state.ctx)   { llama_free(g_state.ctx);         g_state.ctx = nullptr; }
    if (g_state.model) { llama_model_free(g_state.model); g_state.model = nullptr; }
    g_state.vocab = nullptr;
    LOGI("freeModel: released");
}

// -----------------------------------------------------------------------------
// abort.
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_roleplay_harness_NativeLib_abort(JNIEnv*, jobject) {
    g_abort = true;
    LOGI("abort requested");
}

// -----------------------------------------------------------------------------
// generate — the main inference loop.
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_roleplay_harness_NativeLib_generate(
        JNIEnv* env, jobject /* this */,
        jstring jprompt,
        jfloat temperature, jfloat top_p, jint top_k,
        jfloat repetition_penalty, jint max_tokens,
        jobject callback)
{
    g_abort = false;

    if (!g_state.ctx || !g_state.model || !g_state.vocab) {
        LOGE("generate: no model loaded");
        return env->NewStringUTF("");
    }

    // Look up the callback method: interface TokenCallback { void onToken(String); }
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID cbMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!cbMethod) {
        LOGE("generate: onToken method not found on callback");
        env->DeleteLocalRef(cbClass);
        return env->NewStringUTF("");
    }

    // Fresh KV cache for this turn.
    if (!recreateContext()) {
        LOGE("generate: context recreation failed");
        env->DeleteLocalRef(cbClass);
        return env->NewStringUTF("");
    }

    // ---- Tokenize the prompt ----
    const char* prompt_cstr = env->GetStringUTFChars(jprompt, nullptr);
    int prompt_len = (int) std::strlen(prompt_cstr);

    int n_needed = -llama_tokenize(
            g_state.vocab, prompt_cstr, prompt_len,
            nullptr, 0,
            /*add_special=*/false, /*parse_special=*/true);

    std::vector<llama_token> tokens(n_needed);
    int n_tokens = llama_tokenize(
            g_state.vocab, prompt_cstr, prompt_len,
            tokens.data(), n_needed,
            /*add_special=*/false, /*parse_special=*/true);

    env->ReleaseStringUTFChars(jprompt, prompt_cstr);

    if (n_tokens < 0) {
        LOGE("generate: tokenize failed");
        env->DeleteLocalRef(cbClass);
        return env->NewStringUTF("");
    }
    LOGI("generate: prompt tokenized to %d tokens", n_tokens);

    // ---- Build the sampler chain ----
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* chain = llama_sampler_chain_init(sparams);

    const int n_vocab = llama_vocab_n_tokens(g_state.vocab);
    llama_sampler_chain_add(chain,
                            llama_sampler_init_penalties(
                                    n_vocab,
                                    /*penalty_last_n=*/64,
                                    repetition_penalty,
                                    /*penalty_freq=*/0.0f,
                                    /*penalty_present=*/0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, /*min_keep=*/1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ---- Decode the prompt in n_batch-sized chunks ----
    const int n_batch = 512;
    for (int i = 0; i < n_tokens; i += n_batch) {
        int chunk = (n_tokens - i < n_batch) ? (n_tokens - i) : n_batch;
        if (llama_decode(g_state.ctx,
                         llama_batch_get_one(tokens.data() + i, chunk)) != 0) {
            LOGE("generate: prompt decode failed at offset %d", i);
            llama_sampler_free(chain);
            env->DeleteLocalRef(cbClass);
            return env->NewStringUTF("");
        }
    }

    // ---- Generation loop ----
    std::string result;
    char piece_buf[256];

    for (int i = 0; i < max_tokens; i++) {
        if (g_abort) {
            LOGI("generate: aborted at token %d", i);
            break;
        }

        llama_token tok = llama_sampler_sample(chain, g_state.ctx, /*idx=*/-1);

        if (llama_vocab_is_eog(g_state.vocab, tok)) {
            LOGI("generate: end-of-generation at token %d", i);
            break;
        }

        int np = llama_token_to_piece(
                g_state.vocab, tok,
                piece_buf, sizeof(piece_buf),
                /*lstrip=*/0, /*special=*/false);

        if (np > 0) {
            std::string piece(piece_buf, np);
            result += piece;
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, cbMethod, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        if (llama_decode(g_state.ctx, llama_batch_get_one(&tok, 1)) != 0) {
            LOGE("generate: decode failed at token %d", i);
            break;
        }
        // Note: llama_sampler_sample accepts the token into the sampler state
        // internally in this version. Do not call llama_sampler_accept again.
    }

    llama_sampler_free(chain);
    env->DeleteLocalRef(cbClass);

    LOGI("generate: complete, %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}