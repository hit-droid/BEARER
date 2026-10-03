package com.offlineagent.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 智能体主循环：ReAct 风格的 规划 → 执行 → 观察 闭环。
 *
 * 每一步：读取屏幕 → 决定下一步动作 → 执行 → 记录历史 → 继续。
 * 通过 [Flow]<[Event]> 把进度实时暴露给 UI，UI 也可借此展示"思考过程"。
 *
 * ## 决策优先级：知识优先，LLM 兜底
 * 1. **确定性路线**（[RoutePlanner]）：若本地知识库的导航图里已能算出通往目标的点击序列，
 *    就直接按序列执行——不调用大模型，零延迟、无采样随机性。
 * 2. **模型规划**（[Planner]）：知识库信息不足时，把"页面地图 + 当前界面 + 历史"交给本地 LLM 决策。
 *
 * 这样随着使用次数增加，越来越多的任务会被成本低得多的方式 1 覆盖。
 */
class AgentLoop(
    private val planner: Planner,
    private val device: DeviceController,
    private val memory: MemoryStore,
    private val routePlanner: RoutePlanner? = null,
) {

    data class Config(
        val maxSteps: Int = 15,
        val stepDelayMs: Long = 600,
        /** 是否启用"确定性路线优先"（用已积累的导航图直算路径）。 */
        val useRoute: Boolean = true,
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

        /** 命中本地知识库中的确定性路线，后续将按此路线执行（不经模型推理）。 */
        data class Routed(val route: RoutePlanner.Route) : Event

        /** 沿确定性路线执行第 index 步（共 total 步）。 */
        data class RouteStep(val index: Int, val total: Int, val label: String) : Event

        /** 确定性路线已走完，剩余操作交回模型规划。 */
        data class RouteDone(val steps: Int, val target: String) : Event

        /** 路线中途走不通，已回退到模型规划。 */
        data class RouteAbort(val reason: String) : Event
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

        // 确定性路线队列：非空时按队列依次点击，不调用大模型
        var routeQueue: java.util.ArrayDeque<String>? = null
        var routeTotal = 0
        // 尝试次数上限：允许"切到另一个应用后再规划一次"
        var routeAttempts = 0

        repeat(config.maxSteps) {
            val snapshot = runCatching { device.snapshot() }.getOrElse { e ->
                emit(Event.Error("读取界面失败：${e.message}"))
                return@flow
            }
            if (sourcePkg.isBlank()) sourcePkg = snapshot.packageName
            emit(Event.Observation(snapshot))

            val pkg = snapshot.packageName
            val pageKey = snapshot.pageKey

            // ---- 探索阶段：把当前页面与可交互元素登记进文档型知识库 ----
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
            memory.flush()

            val queue = routeQueue
            when {
                // ---- A. 正在沿确定性路线执行 ----
                queue != null && queue.isNotEmpty() -> {
                    val label = queue.removeFirst()
                    val index = routeTotal - queue.size
                    emit(Event.RouteStep(index, routeTotal, label))

                    val action = Action.TapText(label)
                    val res = runCatching { device.execute(action) }
                        .getOrDefault(ActionResult(false, "执行异常"))
                    emit(Event.Executed(action, res.success, res.message))

                    if (res.success) {
                        memory.success(pkg, elementKeyOf(label, ""))
                        history.add(describe(action))
                        recorded.add(ScriptStep(action.toScriptAction(), pageKey, null, true))
                        if (queue.isEmpty()) {
                            routeQueue = null
                            emit(Event.RouteDone(routeTotal, label))
                        }
                    } else {
                        // 路线与现实不符 → 丢弃，交回模型重新规划
                        routeQueue = null
                        memory.fail(pkg, elementKeyOf(label, ""))
                        emit(Event.RouteAbort("路线第 $index 步「$label」未命中，改用模型规划"))
                    }
                    memory.flush()
                    delay(config.stepDelayMs)
                }

                // ---- B. 尚未规划过路线：先尝试用积累的导航图直算路径 ----
                config.useRoute && routePlanner != null && routeAttempts < 2 -> {
                    routeAttempts++
                    val route = routePlanner.plan(goal, pkg, pageKey)

                    if (route != null && route.switchToApp != null) {
                        // 目标疑似位于另一个已探索过的应用：先切过去，下一轮再规划路线
                        emit(Event.Routed(route))
                        val action = Action.OpenApp(route.switchToApp)
                        val res = runCatching { device.execute(action) }
                            .getOrDefault(ActionResult(false, "执行异常"))
                        emit(Event.Executed(action, res.success, res.message))
                        history.add(describe(action))
                        recorded.add(ScriptStep(action.toScriptAction(), pageKey, null, res.success))
                        delay(config.stepDelayMs)
                    } else if (route != null && route.steps.isNotEmpty()) {
                        emit(Event.Routed(route))
                        routeQueue = java.util.ArrayDeque(route.steps)
                        routeTotal = route.steps.size
                    } else if (route != null) {
                        // 已在目标页：没有可执行的路线步骤，剩余细节交由模型完成
                        routeAttempts = 2
                        emit(Event.Routed(route))
                    } else {
                        // 知识不足以规划路线：提示可先探索积累，本轮起全部交给模型
                        routeAttempts = 2
                        emit(
                            Event.RouteAbort(
                                "本地知识库里查不到通往目标的路径，已转模型规划。" +
                                    "建议先点「探索当前应用」让它熟悉一下，成功率会明显更高。",
                            ),
                        )
                    }
                }

                // ---- C. 常规：调用本地模型规划下一步 ----
                else -> {
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

                            if (res.success) {
                                when (action) {
                                    is Action.TapText -> memory.success(pkg, elementKeyOf(action.byText, ""))
                                    is Action.Type -> action.byText?.let { memory.success(pkg, elementKeyOf(it, "")) }
                                    else -> {}
                                }
                                if (action is Action.TapText) {
                                    val after = runCatching { device.snapshot() }.getOrElse { null }
                                    if (after != null && after.pageKey != pageKey) {
                                        memory.recordNavigation(pkg, pageKey, elementKeyOf(action.byText, ""), after.pageKey)
                                    }
                                }
                            } else {
                                if (action is Action.TapText) memory.fail(pkg, elementKeyOf(action.byText, ""))
                            }

                            val afterPage = runCatching { device.snapshot() }.getOrElse { null }?.pageKey
                            recorded.add(ScriptStep(action.toScriptAction(), pageKey, afterPage, res.success))

                            history.add(describe(action))
                            memory.flush()
                            delay(config.stepDelayMs)
                        }
                    }
                }
            }
        }

        emitRecorded()
        emit(Event.Finished("已达到最大步数 ${config.maxSteps}，自动停止。", history.size))
    }

    private fun describe(action: Action): String = action.label()
}
