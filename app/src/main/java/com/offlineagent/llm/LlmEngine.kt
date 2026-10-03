package com.offlineagent.llm

import kotlinx.coroutines.flow.Flow

/**
 * 统一的本地推理接口。
 *
 * 任何实现都必须满足：
 * 1. 全程离线，不发起任何网络请求；
 * 2. [generate] 以流式（Flow<String>）返回 token，便于 UI 实时显示；
 * 3. 加载的是本地 GGUF 模型文件（或内置的规则规划器）。
 */
interface LlmEngine {

    /** 引擎名称，用于 UI 展示与日志。 */
    val name: String

    /** 模型是否已加载完成、可以推理。 */
    val isModelLoaded: Boolean

    /**
     * 引擎是否能遵循"任意指令提示词"，而非只能输出固定格式的动作 JSON。
     *
     * 真实 LLM 为 true，可用于语义标注、目标拆解等辅助任务；
     * 规则式规划器（如 [StubEngine]）只能按既定分支应答，必须声明 false，
     * 以免上层把无关任务（如"给元素写功能说明"）误发给它。
     */
    val canFollowArbitraryInstructions: Boolean get() = true

    /**
     * 加载本地模型。
     * @param modelPath 设备上的 GGUF 绝对路径（通常在应用私有目录）。
     */
    suspend fun load(modelPath: String): Result<Unit>

    /**
     * 生成文本。
     * @param system 系统提示词（定义智能体角色与输出格式）。
     * @param prompt 用户/环境输入（通常是 目标 + 当前界面快照）。
     * @param params 采样参数。
     * @return 逐 token 的文本流。
     */
    fun generate(system: String, prompt: String, params: GenParams = GenParams()): Flow<String>

    /** 释放模型与显存。 */
    fun unload()
}

/**
 * 采样参数。离线小模型建议温度偏低、maxTokens 适中，避免冗长与跑偏。
 */
data class GenParams(
    val temperature: Float = 0.4f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 256,
    val stop: List<String> = emptyList(),
)
