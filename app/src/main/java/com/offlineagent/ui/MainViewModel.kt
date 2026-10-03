package com.offlineagent.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineagent.OfflineAgentApp
import com.offlineagent.automation.AgentAccessibilityService
import com.offlineagent.core.ActionScript
import com.offlineagent.core.AgentLoop
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
                container.agentLoop.run(goal, record = _state.value.recordMode).collect { event ->
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
