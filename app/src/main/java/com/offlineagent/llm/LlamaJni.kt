package com.offlineagent.llm

import android.util.Log
import com.offlineagent.BuildConfig

/**
 * 与 llama.cpp 的 JNI 桥接层。
 *
 * 对应原生实现见 [src/main/jni/llama_jni.cpp]，由 [src/main/jni/CMakeLists.txt]
 * 编译为 libllamajni.so（并链接 llama.cpp 产出的 libllama.so / libggml.so）。
 *
 * 若当前构建未包含原生库（例如仅用 StubEngine 演示 UI，即 [BuildConfig.USE_NATIVE_LLM] 为 false），
 * [nativeAvailable] 为 false，此时不应调用 [load]/[generate]/[free]。
 */
class LlamaJni {

    /** 推理回调：每条 token 触发一次。由原生线程调用。 */
    interface TokenCallback {
        fun onToken(token: String)
    }

    /** 加载 GGUF 模型，返回上下文句柄（0 表示失败）。 */
    external fun load(modelPath: String): Long

    /** 同步解码并逐 token 回调。调用线程必须已 attach JVM。 */
    external fun generate(
        handle: Long,
        system: String,
        prompt: String,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback,
    )

    /** 释放上下文与模型。 */
    external fun free(handle: Long)

    companion object {
        @Volatile
        var nativeAvailable: Boolean = false
            private set

        init {
            nativeAvailable = if (BuildConfig.USE_NATIVE_LLM) {
                try {
                    System.loadLibrary("llamajni")
                    true
                } catch (t: Throwable) {
                    Log.e("LlamaJni", "已开启原生开关但 libllamajni.so 加载失败，请先执行 scripts/build_llama.sh。", t)
                    false
                }
            } else {
                // 关闭原生开关：明确不加载，避免无谓的 UnsatisfiedLinkError 告警。
                false
            }
        }
    }
}
