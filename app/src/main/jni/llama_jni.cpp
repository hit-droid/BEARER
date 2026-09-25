// llama_jni.cpp
// 离线智能体的本地推理胶水层：把 llama.cpp 的 C API 暴露给 Kotlin。
//
// 目标 API 面（经典采样循环，llama.cpp 2023~2024 广泛兼容）：
//   llama_model_load_from_file / llama_model_default_params
//   llama_new_context_with_model / llama_context_default_params
//   llama_tokenize / llama_token_bos / llama_token_eos
//   llama_batch_get_one / llama_decode
//   llama_get_logits / llama_sample_top_p / llama_sample_temp / llama_sample_token
//   llama_token_to_piece / llama_n_vocab / llama_n_ctx
//   llama_free / llama_model_free
//
// 如果你本地 llama.cpp 已切换到新的 sampler 链 / llama_vocab API，
// 只需在对应函数处做少量替换即可（见文件末尾注释）。

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct Session {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineagent_llm_LlamaJni_load(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        LOGE("模型加载失败: %s", path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx        = 2048;
    cparams.n_threads    = 4;          // 可按设备调整；4 在多数中端机较稳
    cparams.n_threads_batch = 4;
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        LOGE("上下文创建失败");
        llama_model_free(model);
        return 0;
    }

    auto* s = new Session();
    s->model = model;
    s->ctx   = ctx;
    LOGI("模型加载成功");
    return reinterpret_cast<jlong>(s);
}

// 把文本编码为 token，返回追加到 out 的 token 数；addBos 控制是否补 BOS。
static int tokenize(const llama_model* model, const std::string& text,
                    std::vector<llama_token>& out, bool addBos) {
    const int n_vocab = llama_n_vocab(model);
    std::vector<llama_token> tmp(n_vocab);
    const int n = llama_tokenize(model, text.c_str(), (int)text.size(),
                                 tmp.data(), (int)tmp.size(), addBos, true);
    if (n <= 0) return 0;
    out.insert(out.end(), tmp.begin(), tmp.begin() + n);
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineagent_llm_LlamaJni_generate(
        JNIEnv* env, jclass, jlong handle, jstring jsystem, jstring jprompt,
        jfloat temp, jint maxTokens, jobject callback) {

    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || !s->ctx) return;

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");

    const char* cSys = env->GetStringUTFChars(jsystem, nullptr);
    const char* cPrompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string system(cSys);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jsystem, cSys);
    env->ReleaseStringUTFChars(jprompt, cPrompt);

    // 简单拼接系统提示与用户输入（如需 chat 模板，可在此调用 llama_apply_chat_template）。
    std::string full;
    if (!system.empty()) full += system + "\n";
    full += prompt;

    llama_context* ctx = s->ctx;
    const int n_vocab = llama_n_vocab(s->model);

    std::vector<llama_token> embd;
    embd.push_back(llama_token_bos(s->model));
    tokenize(s->model, full, embd, false);

    const int n_predict = maxTokens > 0 ? maxTokens : 256;
    int n_past = 0;
    int n_cur  = 0;

    // 复用的候选数组
    std::vector<llama_token_data> candidates(n_vocab);
    llama_token_data_array cand{ candidates.data(), (size_t)n_vocab, -1, -1 };

    char piece[256];

    while (n_cur < n_predict) {
        llama_batch batch = llama_batch_get_one(embd.data() + n_past,
                                                 (int)embd.size() - n_past,
                                                 n_past, 0);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("llama_decode 失败");
            break;
        }
        n_past = (int)embd.size();

        // 采样
        const float* logits = llama_get_logits(ctx);
        for (int i = 0; i < n_vocab; i++) {
            candidates[i] = llama_token_data{ i, logits[i], 0.0f };
        }
        cand.size = (size_t)n_vocab;
        cand.selected = -1;
        llama_sample_top_p(ctx, &cand, 0.9f);
        if (temp > 0.0f) llama_sample_temp(ctx, &cand, temp);
        llama_token id = llama_sample_token(ctx, &cand);

        if (id == llama_token_eos(s->model)) break;

        const int n = llama_token_to_piece(ctx, id, piece, (int)sizeof(piece));
        if (n > 0) {
            std::string txt(piece, n);
            jstring jt = env->NewStringUTF(txt.c_str());
            env->CallVoidMethod(callback, onToken, jt);
            env->DeleteLocalRef(jt);
        }

        embd.push_back(id);
        n_cur++;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineagent_llm_LlamaJni_free(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

// ----------------------------------------------------------------------------
// 新 API 适配提示（llama.cpp 已迁移到 sampler 链 + llama_vocab）：
//   候选数组 → 使用 llama_sampler_chain + llama_sampler_* 构造采样器；
//   llama_token_to_piece(ctx, ...) → llama_token_to_piece(llama_model_get_vocab(model), ...)；
//   llama_get_logits(ctx) → llama_get_logits_ith(ctx, batch.n_tokens - 1)。
// 如需，可在此文件顶部 #define LLAMA_NEW_API 并分别实现。
// ----------------------------------------------------------------------------
