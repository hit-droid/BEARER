package com.offlineagent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 基于 llama.cpp 的真实离线推理引擎。
 *
 * 通过 [LlamaJni] 调用原生 libllamajni.so，模型文件（GGUF）完全在端侧加载与推理，
 * 不依赖任何云端服务。适用于 Q4_K_M 等量化后的 1B~7B 模型（取决于设备内存）。
 */
class LlamaCppEngine : LlmEngine {

    override val name: String = "llama.cpp (GGUF)"

    @Volatile private var handle: Long = 0L

    override val isModelLoaded: Boolean
        get() = handle != 0L

    init {
        require(LlamaJni.nativeAvailable) {
            "llama.cpp 原生库未加载，无法使用 LlamaCppEngine。请先按 README 编译原生层，或改用 StubEngine。"
        }
    }

    override suspend fun load(modelPath: String): Result<Unit> = runCatching {
        withContext(Dispatchers.Default) {
            val h = LlamaJni.load(modelPath)
            require(h != 0L) { "模型加载失败，请检查路径与 GGUF 格式是否受支持。" }
            handle = h
        }
    }

    override fun generate(
        system: String,
        prompt: String,
        params: GenParams,
    ): Flow<String> = callbackFlow {
        val callback = object : LlamaJni.TokenCallback {
            override fun onToken(token: String) {
                try {
                    trySend(token)
                } catch (_: ClosedSendChannelException) {
                    // 收集方已取消，忽略剩余 token
                }
            }
        }
        launch(Dispatchers.Default) {
            try {
                LlamaJni.generate(
                    handle = handle,
                    system = system,
                    prompt = prompt,
                    temperature = params.temperature,
                    maxTokens = params.maxTokens,
                    callback = callback,
                )
            } finally {
                close()
            }
        }
        awaitClose { /* 取消时原生仍在跑，token 会被 trySend 丢弃 */ }
    }

    override fun unload() {
        if (handle != 0L) {
            LlamaJni.free(handle)
            handle = 0L
        }
    }
}
