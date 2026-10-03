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
    }

    fun run(goal: String, config: Config = Config()): Flow<Event> = flow {
        val history = mutableListOf<String>()

        repeat(config.maxSteps) {
            val snapshot = runCatching { device.snapshot() }.getOrElse { e ->
                emit(Event.Error("读取界面失败：${e.message}"))
                return@flow
            }
            emit(Event.Observation(snapshot))

            // 探索阶段：把当前界面可见的可交互元素登记进本地记忆
            val clickableTexts = snapshot.nodes
                .filter { it.clickable }
                .map { it.text.ifBlank { it.desc } }
                .filter { !it.isNullOrBlank() }
                .map { it!! }
            memory.discover(snapshot.packageName, clickableTexts)

            val plan = planner.next(goal, snapshot, history, memory.knownElements(snapshot.packageName))
            val action = plan.toAction()
            emit(Event.Planning(action, plan.reason))

            when (action) {
                is Action.Done -> {
                    emit(Event.Finished(action.result, history.size))
                    return@flow
                }
                is Action.Ask -> {
                    emit(Event.NeedInput(action.question))
                    return@flow
                }
                else -> {
                    val res = runCatching { device.execute(action) }
                        .getOrDefault(ActionResult(false, "执行异常"))
                    emit(Event.Executed(action, res.success, res.message))
                    // 成功操作后强化记忆（记录该元素"有用"）
                    if (res.success) {
                        when (action) {
                            is Action.TapText -> memory.remember(snapshot.packageName, action.byText)
                            is Action.Type -> action.byText?.let { memory.remember(snapshot.packageName, it) }
                            else -> {}
                        }
                    }
                    history.add(describe(action))
                    delay(config.stepDelayMs)
                }
            }
        }

        emit(Event.Finished("已达到最大步数 ${config.maxSteps}，自动停止。", history.size))
    }

    private fun describe(action: Action): String = action.label()
}
