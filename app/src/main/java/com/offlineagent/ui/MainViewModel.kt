package com.offlineagent.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineagent.OfflineAgentApp
import com.offlineagent.automation.AgentAccessibilityService
import com.offlineagent.core.AgentLoop
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
)

/**
 * 主页状态机：驱动智能体循环并把事件转成可展示日志与实时观察。
 */
class MainViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val container = (app as OfflineAgentApp).container

    private val _state = MutableStateFlow(MainUiState())
    val state = _state.asStateFlow()

    private var job: kotlinx.coroutines.Job? = null

    init {
        refreshStatus()
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
            runCatching {
                container.agentLoop.run(goal).collect { event ->
                    when (event) {
                        is AgentLoop.Event.Planning ->
                            append("PLAN", "规划 → ${event.action.label()}${event.reason?.let { "  (${it})" } ?: ""}")
                        is AgentLoop.Event.Observation -> {
                            _state.update { it.copy(observation = event.snapshot.toPromptText().take(2000)) }
                            append("OBS", "观察：${event.snapshot.packageName}（${event.snapshot.nodes.size} 节点）")
                        }
                        is AgentLoop.Event.Executed ->
                            append(if (event.success) "ACT" else "FAIL", "执行 ${event.action.label()} → ${event.message}")
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
