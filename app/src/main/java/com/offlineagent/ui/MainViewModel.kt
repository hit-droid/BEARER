package com.offlineagent.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineagent.OfflineAgentApp
import com.offlineagent.automation.AgentAccessibilityService
import com.offlineagent.core.ActionScript
import com.offlineagent.core.AgentLoop
import com.offlineagent.core.Explorer
import com.offlineagent.core.ReplayRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val time: String, val level: String, val text: String)

data class MainUiState(
    val running: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val observation: String = "",
    val engineName: String = "",
    val accessibilityEnabled: Boolean = false,
    val modelStatus: String = "",
    /** 录制模式：本次运行结束后把动作序列保存为可回放脚本。 */
    val recordMode: Boolean = false,
    /** 已保存的脚本列表（设置/脚本页刷新后填入）。 */
    val scripts: List<ActionScript> = emptyList(),
)

/**
 * 主页状态机：驱动智能体循环/回放并把事件转成可展示日志与实时观察。
 */
class MainViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val container = (app as OfflineAgentApp).container

    private val _state = MutableStateFlow(MainUiState())
    val state = _state.asStateFlow()

    private var job: kotlinx.coroutines.Job? = null

    init {
        refreshStatus()
        loadScripts()
    }

    fun refreshStatus() {
        val name = container.engineName
        _state.update {
            it.copy(
                engineName = name,
                accessibilityEnabled = AgentAccessibilityService.instance != null,
                modelStatus = if (name.contains("Stub")) "内置规划器（无需模型）" else "待加载模型",
            )
        }
    }

    fun setRecordMode(on: Boolean) {
        _state.update { it.copy(recordMode = on) }
    }

    /** 刷新已保存脚本列表。 */
    fun loadScripts() {
        _state.update { it.copy(scripts = container.scriptStore.list()) }
    }

    fun start(goal: String) {
        if (goal.isBlank() || _state.value.running) return
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, logs = emptyList(), observation = "") }
            container.ensureModelLoaded()
                .onFailure { e ->
                    append("ERROR", "推理引擎初始化失败：${e.message}")
                    _state.update { it.copy(running = false) }
                    return@launch
                }
            append("INFO", "推理引擎：${container.engineName}")
            if (!_state.value.accessibilityEnabled) {
                append("WARN", "无障碍服务未开启，自动化动作不会真正执行（可在设置中开启）。")
            }
            if (_state.value.recordMode) append("REC", "录制模式已开启：结束后自动保存脚本")
            runCatching {
                container.agentLoop.run(
                    goal = goal,
                    config = AgentLoop.Config(useRoute = container.settings.routeFirst),
                    record = _state.value.recordMode,
                ).collect { event ->
                    when (event) {
                        is AgentLoop.Event.Planning ->
                            append("PLAN", "规划 → ${event.action.label()}${event.reason?.let { "  (${it})" } ?: ""}")
                        is AgentLoop.Event.Observation -> {
                            val snap = event.snapshot
                            _state.update {
                                it.copy(
                                    observation = "页面：「${snap.title}」（${snap.packageName}）\n" +
                                        snap.toPromptText().take(2000),
                                )
                            }
                            append("OBS", "观察：${snap.packageName}「${snap.title}」(${snap.nodes.size} 节点)")
                        }
                        is AgentLoop.Event.Executed ->
                            append(if (event.success) "ACT" else "FAIL", "执行 ${event.action.label()} → ${event.message}")
                        is AgentLoop.Event.Recorded -> {
                            container.scriptStore.save(event.script)
                            loadScripts()
                            append("REC", "已保存脚本「${event.script.name}」（${event.script.steps.size} 步）")
                        }
                        is AgentLoop.Event.Finished ->
                            append("DONE", "完成：${event.result}（共 ${event.steps} 步）")
                        // 知识优先：命中确定性路线时不消耗推理，直接按已积累路径执行
                        is AgentLoop.Event.Routed ->
                            append("ROUTE", "命中本地知识路线 → ${event.route.describe()}")
                        is AgentLoop.Event.RouteStep ->
                            append("ROUTE", "路线 ${event.index}/${event.total}：点击「${event.label}」")
                        is AgentLoop.Event.RouteDone ->
                            append("ROUTE", "路线走完（${event.steps} 步），剩余细节交给${container.engineName}")
                        is AgentLoop.Event.RouteAbort ->
                            append("WARN", event.reason)
                        is AgentLoop.Event.NeedInput ->
                            append("ASK", "需要澄清：${event.question}")
                        is AgentLoop.Event.Error ->
                            append("ERROR", event.message)
                    }
                }
            }.onFailure { append("ERROR", "循环异常：${it.message}") }
            _state.update { it.copy(running = false) }
            append("INFO", "智能体已停止。")
        }
    }

    /** 回放一个已保存脚本：按步骤执行，不再规划。 */
    fun replay(script: ActionScript) {
        if (_state.value.running) return
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, logs = emptyList(), observation = "") }
            append("INFO", "开始回放脚本「${script.name}」（${script.steps.size} 步）")
            if (!_state.value.accessibilityEnabled) {
                append("WARN", "无障碍服务未开启，自动化动作不会真正执行（可在设置中开启）。")
            }
            runCatching {
                container.replayRunner.run(script).collect { ev ->
                    when (ev) {
                        is ReplayRunner.Event.Step ->
                            append("STEP", "步骤 ${ev.index + 1}/${script.steps.size}：${ev.label}")
                        is ReplayRunner.Event.Executed ->
                            append(if (ev.success) "ACT" else "FAIL", "执行 ${ev.action.label()} → ${ev.message}")
                        is ReplayRunner.Event.Finished ->
                            append("DONE", ev.result)
                        is ReplayRunner.Event.Error ->
                            append("ERROR", ev.message)
                    }
                }
            }.onFailure { append("ERROR", "回放异常：${it.message}") }
            _state.update { it.copy(running = false) }
            append("INFO", "回放已停止。")
        }
    }

    /**
     * 自动探索当前前台应用：广度优先遍历其页面，沉淀"页面地图 + 导航关系"到本地知识库。
     * 不要求一定加载模型——无模型时仍能探索（元素描述退化为启发式），有模型则会补语义标注。
     */
    fun exploreApp() {
        if (_state.value.running) return
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, logs = emptyList(), observation = "") }
            append("INFO", "开始自动探索：只点击，不输入文本、不触碰危险控件")
            if (!_state.value.accessibilityEnabled) {
                append("WARN", "无障碍服务未开启，探索无法执行（可在设置中开启）。")
            }

            // 有可用模型时顺便用于语义标注；加载失败不影响探索本身
            container.ensureModelLoaded()
                .onSuccess { if (container.annotator.available) append("INFO", "已启用本地模型语义标注") }
                .onFailure { append("INFO", "未加载本地模型：元素描述退化为启发式推断") }

            runCatching {
                container.explorer.explore().collect { ev ->
                    when (ev) {
                        is Explorer.Event.Visiting -> {
                            append("PAGE", "探索页面「${ev.title}」（第 ${ev.discovered} 页）")
                            _state.update {
                                it.copy(
                                    observation = "探索中：第 ${ev.discovered} 页「${ev.title}」\n" +
                                        "页面指纹 ${ev.pageKey}\n\n${container.memory.stats()}",
                                )
                            }
                        }
                        is Explorer.Event.Probing ->
                            append("EXPLORE", "试探「${ev.label}」")
                        is Explorer.Event.Navigation ->
                            append("NAV", "发现路径：点「${ev.via}」→ 进入 ${ev.to}")
                        is Explorer.Event.Backtracking ->
                            append("BACK", "本页已探完，返回上层")
                        is Explorer.Event.Annotated ->
                            append("LLM", ev.count.takeIf { it > 0 }
                                ?.let { "语义标注完成：$it 个元素写入知识库" }
                                ?: "无可标注元素（或本地模型不可用）")
                        is Explorer.Event.Finished ->
                            append("DONE", "探索结束：页面 ${ev.pages} · 元素 ${ev.elements} · 步数 ${ev.steps}")
                        is Explorer.Event.Error ->
                            append("ERROR", ev.message)
                    }
                }
            }.onFailure { append("ERROR", "探索异常：${it.message}") }

            _state.update { it.copy(running = false) }
            append("INFO", "探索已停止。可在「设置 → 本地知识库」预览学到的内容。")
        }
    }

    fun deleteScript(id: String) {
        container.scriptStore.delete(id)
        loadScripts()
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false) }
        append("INFO", "已请求停止。")
    }

    private fun append(level: String, text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update {
            val next = (it.logs + LogEntry(t, level, text)).takeLast(MAX_LOGS)
            it.copy(logs = next)
        }
    }

    companion object {
        private const val MAX_LOGS = 300
    }
}
