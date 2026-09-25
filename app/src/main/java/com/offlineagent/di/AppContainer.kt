package com.offlineagent.di

import android.app.Application
import com.offlineagent.BuildConfig
import com.offlineagent.automation.AccessibilityAutomator
import com.offlineagent.core.AgentLoop
import com.offlineagent.core.Planner
import com.offlineagent.llm.LlamaCppEngine
import com.offlineagent.llm.LlamaJni
import com.offlineagent.llm.LlmEngine
import com.offlineagent.llm.StubEngine

/**
 * 轻量级依赖装配（不引入 DI 框架，手动连接即可）。
 *
 * 引擎选择策略：
 * - 若原生 llama.cpp 库可用 → [LlamaCppEngine]（需用户提供 GGUF 模型）；
 * - 否则降级为 [StubEngine]，让应用无需模型也能跑通自动化闭环。
 */
class AppContainer(private val app: Application) {

    val settings = Settings(app)

    val automator = AccessibilityAutomator()

    private val engine: LlmEngine by lazy {
        // 仅在「开启原生开关」且「so 已成功加载」时启用真实推理，否则降级为规则规划器。
        if (BuildConfig.USE_NATIVE_LLM && LlamaJni.nativeAvailable) LlamaCppEngine() else StubEngine()
    }

    val planner = Planner(engine)

    val agentLoop = AgentLoop(planner, automator)

    val engineName: String get() = engine.name

    /** 确保模型已加载（仅对真实引擎有意义）。 */
    suspend fun ensureModelLoaded(): Result<Unit> {
        if (engine is LlamaCppEngine && !engine.isModelLoaded) {
            val path = settings.modelPath
            if (path.isBlank()) {
                return Result.failure(IllegalStateException("未配置模型路径，无法使用真实推理引擎。"))
            }
            return engine.load(path)
        }
        return Result.success(Unit)
    }
}
