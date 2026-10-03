package com.offlineagent.di

import android.app.Application
import com.offlineagent.BuildConfig
import com.offlineagent.automation.AccessibilityAutomator
import com.offlineagent.core.AgentLoop
import com.offlineagent.core.Explorer
import com.offlineagent.core.MemoryStore
import com.offlineagent.core.Planner
import com.offlineagent.core.ReplayRunner
import com.offlineagent.core.ScriptStore
import com.offlineagent.llm.ElementAnnotator
import com.offlineagent.llm.LlamaCppEngine
import com.offlineagent.llm.LlamaJni
import com.offlineagent.llm.LlmEngine
import com.offlineagent.llm.StubEngine
import java.io.File

/**
 * 轻量级依赖装配（不引入 DI 框架，手动连接即可）。
 *
 * 引擎选择策略：
 * - 若原生 llama.cpp 库可用 → [LlamaCppEngine]（需用户提供 GGUF 模型）；
 * - 否则降级为 [StubEngine]，让应用无需模型也能跑通自动化闭环。
 */
class AppContainer(private val app: Application) {

    val settings = Settings(app)

    /** 本地记忆库：持久化探索到的 UI 元素，离线辅助决策。 */
    val memory = MemoryStore(File(app.filesDir, "offline_agent_memory.json"))

    /** 本地脚本库：持久化录制得到的可回放自动化脚本。 */
    val scriptStore = ScriptStore(File(app.filesDir, "scripts"))

    val automator = AccessibilityAutomator()

    private val engine: LlmEngine by lazy {
        // 仅在「开启原生开关」且「so 已成功加载」时启用真实推理，否则降级为规则规划器。
        if (BuildConfig.USE_NATIVE_LLM && LlamaJni.nativeAvailable) LlamaCppEngine() else StubEngine()
    }

    val planner = Planner(engine)

    val agentLoop = AgentLoop(planner, automator, memory)

    /** 脚本回放器：按已录制脚本逐步执行，无需重新规划。 */
    val replayRunner = ReplayRunner(automator, memory)

    /**
     * UI 元素语义标注器：有真实本地模型时，把知识库中的元素清单升级为
     * "带功能描述的文档"；否则 [ElementAnnotator.available] 为 false，自动走启发式。
     */
    val annotator = ElementAnnotator(engine)

    /** 自动探索器：自主遍历目标 App 的页面，沉淀页面地图与导航关系。 */
    val explorer = Explorer(automator, memory, annotator)

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
