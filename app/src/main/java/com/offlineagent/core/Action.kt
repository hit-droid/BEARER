package com.offlineagent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 智能体可执行的基本动作。由 [Planner] 从 LLM 输出解析得到，交给自动化层执行。
 *
 * 坐标类动作（Tap/Swipe）使用屏幕像素坐标；文本类动作（byText）由自动化层在
 * 当前界面快照中匹配可见节点，鲁棒性更好，是首选。
 */
sealed class Action {

    /** 点击屏幕坐标 (x, y)。 */
    data class Tap(val x: Int, val y: Int) : Action()

    /** 点击界面上文本/描述匹配 [byText] 的可见节点。 */
    data class TapText(val byText: String) : Action()

    /** 在匹配 [byText] 的可编辑节点中输入 [text]（为空则先点中再输入）。 */
    data class Type(val byText: String?, val text: String) : Action()

    /** 从 (fromX, fromY) 滑动到 (toX, toY)。 */
    data class Swipe(
        val fromX: Int, val fromY: Int,
        val toX: Int, val toY: Int,
    ) : Action()

    /** 打开指定包名的应用。 */
    data class OpenApp(val packageName: String) : Action()

    /** 返回桌面。 */
    data object GoHome : Action()

    /** 系统返回键。 */
    data object Back : Action()

    /** 等待 [ms] 毫秒，用于等待界面加载。 */
    data class Wait(val ms: Int) : Action()

    /** 向某方向滚动列表：up / down / left / right。 */
    data class Scroll(val direction: String) : Action()

    /** 任务完成。 */
    data class Done(val result: String) : Action()

    /** 需要向用户提问/澄清后才能继续。 */
    data class Ask(val question: String) : Action()
}

/**
 * 规划器从 LLM 输出中解析的 JSON 数据载体（扁平结构，便于模型稳定输出）。
 * 通过 [toAction] 转换为领域动作 [Action]。
 */
@Serializable
data class ActionPlan(
    @SerialName("action") val action: String,
    @SerialName("reason") val reason: String? = null,
    @SerialName("x") val x: Int? = null,
    @SerialName("y") val y: Int? = null,
    @SerialName("byText") val byText: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("fromX") val fromX: Int? = null,
    @SerialName("fromY") val fromY: Int? = null,
    @SerialName("toX") val toX: Int? = null,
    @SerialName("toY") val toY: Int? = null,
    @SerialName("packageName") val packageName: String? = null,
    @SerialName("ms") val ms: Int? = null,
    @SerialName("direction") val direction: String? = null,
    @SerialName("result") val result: String? = null,
    @SerialName("question") val question: String? = null,
)

/** 将 JSON 载体转换为领域动作；非法/未知动作回退为等待，保证循环不崩。 */
fun ActionPlan.toAction(): Action = when (action.lowercase()) {
    "tap" -> if (byText != null) Action.TapText(byText)
             else if (x != null && y != null) Action.Tap(x, y)
             else Action.Wait(500)

    "type" -> Action.Type(byText, text ?: "")

    "swipe" -> if (fromX != null && fromY != null && toX != null && toY != null)
                   Action.Swipe(fromX, fromY, toX, toY)
               else Action.Wait(300)

    "open_app" -> if (packageName != null) Action.OpenApp(packageName) else Action.Wait(300)

    "go_home", "home" -> Action.GoHome
    "back" -> Action.Back
    "wait" -> Action.Wait((ms ?: 500).coerceIn(100, 5000))
    "scroll" -> Action.Scroll(direction ?: "down")

    "done", "finish", "complete" -> Action.Done(result ?: "已完成")
    "ask", "question" -> Action.Ask(question ?: "需要更多信息才能继续")

    else -> Action.Wait(500)
}

/** 动作的可读标签，用于日志与 UI 展示。 */
fun Action.label(): String = when (this) {
    is Action.Tap -> "点击($x,$y)"
    is Action.TapText -> "点击文本=\"$byText\""
    is Action.Type -> "输入\"$text\""
    is Action.Swipe -> "滑动($fromX,$fromY)→($toX,$toY)"
    is Action.OpenApp -> "打开应用 $packageName"
    is Action.GoHome -> "返回桌面"
    is Action.Back -> "返回"
    is Action.Wait -> "等待 ${ms}ms"
    is Action.Scroll -> "滚动 $direction"
    is Action.Done -> "完成"
    is Action.Ask -> "提问"
}
