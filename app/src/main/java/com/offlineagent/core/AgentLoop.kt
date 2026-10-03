package com.offlineagent.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 智能体主循环：ReAct 风格的 规划 → 执行 → 观察 闭环。
 *
 * 每一步：读取屏幕 → 规划器决定下一步动作 → 执行 → 记录历史 → 继续。
 * 通过 [Flow]<[Event]> 把进度实时暴露给 UI，UI 也可借此展示"思考过程"。
 */
class AgentLoop(
    private val planner: Planner,
    private val device: DeviceController,
    private val memory: MemoryStore,
) {

    data class Config(
        val maxSteps: Int = 15,
        val stepDelayMs: Long = 600,
    )

    sealed interface Event {
        /** 规划器给出下一步动作（reason 来自模型，便于解释）。 */
        data class Planning(val action: Action, val reason: String?) : Event

        /** 观察到的最新界面快照。 */
        data class Observation(val snapshot: UiSnapshot) : Event

        /** 动作执行结果。 */
        data class Executed(val action: Action, val success: Boolean, val message: String) : Event

        /** 任务完成。 */
        data class Finished(val result: String, val steps: Int) : Event

        /** 需要用户澄清，循环暂停。 */
        data class NeedInput(val question: String) : Event

        /** 发生错误，循环终止。 */
        data class Error(val message: String) : Event

        /**
         * 录制产物：当 [run] 开启 [record] 时，在循环结束时（或中途因 Done/Ask 退出时）
         * 发出，包含本次任务可用的可回放脚本。调用方据此落盘到 [ScriptStore]。
         */
        data class Recorded(val script: ActionScript) : Event
    }

    fun run(goal: String, config: Config = Config(), record: Boolean = false): Flow<Event> = flow {
        val history = mutableListOf<String>()
        val recorded = mutableListOf<ScriptStep>()
        var sourcePkg = ""

        // 局部挂起函数：把已累积的步骤打包成脚本并发出（仅在录制模式）
        suspend fun emitRecorded(): ActionScript? {
            if (!record || recorded.isEmpty()) return null
            val script = ActionScript(
                id = "rec_${System.currentTimeMillis().toString(36)}",
                name = goal.take(40).ifBlank { "未命名任务" },
                goal = goal,
                packageName = sourcePkg,
                createdAt = System.currentTimeMillis(),
                steps = recorded.toList(),
            )
            emit(Event.Recorded(script))
            return script
        }

        repeat(config.maxSteps) {
            val snapshot = runCatching { device.snapshot() }.getOrElse { e ->
                emit(Event.Error("读取界面失败：${e.message}"))
                return@flow
            }
            if (sourcePkg.isBlank()) sourcePkg = snapshot.packageName
            emit(Event.Observation(snapshot))

            // ---- 探索阶段：把当前页面与可交互元素登记进文档型知识库 ----
            val pkg = snapshot.packageName
            val pageKey = snapshot.pageKey
            val visible = snapshot.nodes.filter { it.clickable || it.editable || it.scrollable }
            val keys = mutableListOf<String>()
            for (n in visible) {
                val key = elementKeyOf(n.text, n.desc)
                keys.add(key)
                val role = inferRole(n.className, n.editable, n.clickable)
                val fn = inferFunction(n.text, n.desc, n.editable, role)
                memory.recordElement(pkg, key, n.text, n.desc, fn, role, pageKey)
            }
            memory.recordPage(pkg, pageKey, snapshot.title, keys)

            // 注入"页面地图 + 导航"文档，让规划器（本地 LLM）参考
            val knowledgeDoc = memory.describe(pkg, pageKey)
            val plan = planner.next(goal, snapshot, history, knowledgeDoc)
            val action = plan.toAction()
            emit(Event.Planning(action, plan.reason))

            when (action) {
                is Action.Done -> {
                    emitRecorded()
                    emit(Event.Finished(action.result, history.size))
                    return@flow
                }
                is Action.Ask -> {
                    emitRecorded()
                    emit(Event.NeedInput(action.question))
                    return@flow
                }
                else -> {
                    val res = runCatching { device.execute(action) }
                        .getOrDefault(ActionResult(false, "执行异常"))
                    emit(Event.Executed(action, res.success, res.message))

                    // 成功操作后强化该元素（"有用"）
                    if (res.success) {
                        when (action) {
                            is Action.TapText -> memory.success(pkg, elementKeyOf(action.byText, ""))
                            is Action.Type -> action.byText?.let { memory.success(pkg, elementKeyOf(it, "")) }
                            else -> {}
                        }
                        // 点击文本后若页面切换，记录一条导航边
                        if (action is Action.TapText) {
                            val after = runCatching { device.snapshot() }.getOrElse { null }
                            if (after != null && after.pageKey != pageKey) {
                                memory.recordNavigation(pkg, pageKey, elementKeyOf(action.byText, ""), after.pageKey)
                            }
                        }
                    } else {
                        if (action is Action.TapText) memory.fail(pkg, elementKeyOf(action.byText, ""))
                    }

                    // 录制：记录本步动作 + 前后页面 + 成功与否
                    val afterPage = runCatching { device.snapshot() }.getOrElse { null }?.pageKey
                    recorded.add(ScriptStep(action.toScriptAction(), pageKey, afterPage, res.success))

                    history.add(describe(action))
                    // 每步统一落盘一次（探索登记只更新内存，避免频繁全量写文件）
                    memory.flush()
                    delay(config.stepDelayMs)
                }
            }
        }

        emitRecorded()
        emit(Event.Finished("已达到最大步数 ${config.maxSteps}，自动停止。", history.size))
    }

    private fun describe(action: Action): String = action.label()
}
