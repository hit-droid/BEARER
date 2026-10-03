package com.offlineagent.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 脚本回放器：给定一个 [ActionScript]，逐步执行其中的动作，**不再调用规划器**，
 * 因此更快、更确定。适合"演示一次、重复百次"的高频任务。
 *
 * 与 [AgentLoop] 不同，回放不依赖本地 LLM，用 [StubEngine] 也能运行。
 * 回放前会读取当前界面用于诊断（页面漂移提示），但执行仍按脚本硬性进行，
 * 因为坐标/byText 动作本身已自带足够定位信息。
 */
class ReplayRunner(
    private val device: DeviceController,
    private val memory: MemoryStore? = null,
) {

    sealed interface Event {
        /** 即将执行第 index 步。 */
        data class Step(val index: Int, val action: Action, val label: String) : Event

        /** 第 index 步执行结果。 */
        data class Executed(val index: Int, val action: Action, val success: Boolean, val message: String) : Event

        /** 回放结束。 */
        data class Finished(val result: String, val total: Int, val failed: Int) : Event

        /** 发生错误，回放终止。 */
        data class Error(val message: String) : Event
    }

    fun run(script: ActionScript, config: Config = Config()): Flow<Event> = flow {
        var failed = 0

        script.steps.forEachIndexed { i, step ->
            val action = step.action.toAction()
            val label = step.action.label.ifBlank { action.label() }
            emit(Event.Step(i, action, label))

            // 执行前读取界面用于诊断（不阻塞执行，仅记录页面漂移）
            val before = runCatching { device.snapshot() }.getOrNull()
            if (before != null && step.pageBefore.isNotEmpty() && before.pageKey != step.pageBefore) {
                emit(
                    Event.Executed(
                        i, action, true,
                        "页面漂移：录制于 ${step.pageBefore}，当前为 ${before.pageKey}，仍按脚本执行",
                    ),
                )
            }

            val res = runCatching { device.execute(action) }.getOrDefault(ActionResult(false, "执行异常"))
            emit(Event.Executed(i, action, res.success, res.message))

            if (res.success) {
                // 回放成功也强化记忆，长期提升规划质量
                when (action) {
                    is Action.TapText -> memory?.success(before?.packageName ?: "", elementKeyOf(action.byText, ""))
                    is Action.Type -> action.byText?.let { memory?.success(before?.packageName ?: "", elementKeyOf(it, "")) }
                    else -> {}
                }
            } else {
                failed++
            }

            delay(config.stepDelayMs)
        }

        emit(
            Event.Finished(
                "回放完成：共 ${script.steps.size} 步，失败 $failed 步。",
                script.steps.size,
                failed,
            ),
        )
    }

    data class Config(val stepDelayMs: Long = 300)
}
