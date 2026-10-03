package com.offlineagent.core

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json

/**
 * 规划器：把「目标 + 当前界面 + 历史」交给本地推理引擎，得到下一步 [Action]。
 *
 * 这是智能体的"大脑"。离线场景下它对小模型很敏感，因此 prompt 做了强约束：
 * 1) 明确给出可用动作的 JSON schema；
 * 2) 要求只输出一个 JSON 对象（便于稳定解析）；
 * 3) 提供历史步骤，帮助模型避免重复点击、识别任务是否已完成。
 */
class Planner(private val engine: LlmEngine) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 计算下一步动作。
     * @param goal 用户目标
     * @param snapshot 当前界面快照
     * @param history 已执行的步骤摘要（用于避免死循环）
     * @param knowledgeDoc 本地知识库导出的"页面地图 + 导航"文档（探索积累），用于辅助决策
     */
    suspend fun next(
        goal: String,
        snapshot: UiSnapshot,
        history: List<String>,
        knowledgeDoc: String = "",
    ): ActionPlan {
        val system = buildSystemPrompt()
        val user = buildUserPrompt(goal, snapshot, history, knowledgeDoc)

        val raw = try {
            engine.generate(system, user).toList().joinToString("")
        } catch (e: Exception) {
            return ActionPlan(action = "done", result = "推理失败：${e.message}")
        }

        return parsePlan(raw) ?: ActionPlan(
            action = "wait",
            reason = "无法解析模型输出，安全等待 500ms",
        )
    }

    private fun buildSystemPrompt(): String = """
你是一个完全离线运行的安卓自动化智能体。你根据用户目标和当前屏幕内容，决定下一步要执行的唯一动作。
只能从下面的动作中选择一个，并以 JSON 对象输出（不要输出多余解释）：

动作 schema：
- {"action":"tap","byText":"可见文本或描述"}          点击文本匹配的节点
- {"action":"tap","x":100,"y":300}                   点击屏幕坐标
- {"action":"type","byText":"搜索框","text":"要输入的内容"}  在输入框输入
- {"action":"swipe","fromX":0,"fromY":0,"toX":0,"toY":0}   滑动
- {"action":"open_app","packageName":"com.xxx"}        打开应用
- {"action":"scroll","direction":"down"}              滚动（up/down/left/right）
- {"action":"back"}                                   系统返回
- {"action":"go_home"}                                回到桌面
- {"action":"wait","ms":500}                          等待界面加载
- {"action":"done","result":"完成说明"}               任务完成
- {"action":"ask","question":"向用户提问"}            需要澄清
- {"action":"tap_grid","row":0,"col":0}             按网格单元点按（row∈[0,9], col∈[0,5]），用于无文字标签的控件

规则：
1. 优先用 byText 点击可见节点；只有确实知道坐标时才用坐标。
2. 先观察历史步骤，避免重复点击同一个元素。
3. "本地知识库"给出当前页面的可交互元素及其用途，以及"点某元素可直达某页"的导航关系；若目标涉及某页面，优先沿已知导航前往。
4. 目标明显达成时输出 done。
5. 只输出一个 JSON 对象，字段用双引号。
""".trimIndent()

    private fun buildUserPrompt(
        goal: String,
        snapshot: UiSnapshot,
        history: List<String>,
        knowledgeDoc: String,
    ): String {
        val hist = if (history.isEmpty()) "(无)" else history.joinToString("\n  ")
        val memory = if (knowledgeDoc.isBlank()) "(暂无，本次执行中会逐步探索)" else knowledgeDoc
        return """
目标：$goal
历史步骤：
  $hist
本地知识库（页面地图与导航，探索积累，可信参考）：
$memory
当前界面：
${snapshot.toPromptText()}
""".trimIndent()
    }

    private fun parsePlan(raw: String): ActionPlan? {
        val jsonText = extractJson(raw) ?: return null
        return runCatching { json.decodeFromString<ActionPlan>(jsonText) }.getOrNull()
    }

    /** 从模型可能夹带的文本中提取第一个完整 JSON 对象。 */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
