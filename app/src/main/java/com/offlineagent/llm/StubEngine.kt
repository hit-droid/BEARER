package com.offlineagent.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 规则式"规划器"，用于在无 GGUF 模型时也能演示完整的 规划→执行→观察 闭环。
 *
 * 它不调用任何大模型，而是用简单的启发式从「目标 + 界面快照」里推断下一步 [Action] 的 JSON。
 * 这样开发者无需下载模型、无需 NDK 即可先在真机/模拟器上看清自动化流程。
 *
 * 真实部署时把 [LlamaCppEngine] 作为默认引擎即可，本类仅作降级/演示。
 */
class StubEngine : LlmEngine {

    override val name: String = "Stub 规划器（无模型）"
    override val isModelLoaded: Boolean = true

    // 常见应用关键词 → 包名映射，用于"打开 XX"类目标
    private val appMap = mapOf(
        "设置" to "com.android.settings",
        "计算器" to "com.android.calculator2",
        "相机" to "com.android.camera2",
        "相册" to "com.android.gallery3d",
        "浏览器" to "com.android.chrome",
        "日历" to "com.android.calendar",
        "电话" to "com.android.dialer",
        "短信" to "com.android.messaging",
        "文件" to "com.android.documentsui",
    )

    override suspend fun load(modelPath: String): Result<Unit> = Result.success(Unit)

    override fun generate(system: String, prompt: String, params: GenParams): Flow<String> = flow {
        // 模拟"思考"延迟，让 UI 有真实节奏感
        delay(250)
        emit(reason(prompt))
    }

    override fun unload() = Unit

    /**
     * 从 prompt 中解析目标与可点击节点，返回下一步动作 JSON。
     * prompt 约定格式（见 [com.offlineagent.core.Planner]）：
     *   目标：<goal>
     *   当前界面：
     *   <index>. [clickable] text="..." desc="..." bounds=[x1,y1,x2,y2]
     */
    private fun reason(prompt: String): String {
        val goal = prompt.lineSequence()
            .firstOrNull { it.startsWith("目标：") }
            ?.removePrefix("目标：")?.trim().orEmpty()

        val nodes = parseNodes(prompt)

        // 1) 打开/启动应用
        appMap.entries.firstOrNull { (kw, _) -> goal.contains(kw) }
            ?.let { (kw, pkg) ->
                if (goal.contains("打开") || goal.contains("启动") || goal.contains("进入")) {
                    return json("open_app", mapOf("package" to pkg, "hint" to kw))
                }
            }

        // 2) 返回/首页
        if (goal.contains("返回") || goal.contains("首页") || goal.contains("桌面")) {
            return json("go_home", emptyMap())
        }

        // 3) 在界面上寻找与目标文本相关的可点击节点
        val clickable = nodes.filter { it.clickable }
        clickable.firstOrNull { goal.contains(it.text) || it.text.contains(goal.take(4)) }
            ?.let { return json("tap", mapOf("byText" to it.text)) }

        // 4) 输入类
        if (goal.contains("输入") || goal.contains("搜索") || goal.contains("填写")) {
            val editable = nodes.firstOrNull { it.editable }
            val text = goal.substringAfter("输入").substringAfter("搜索").trim().takeIf { it.isNotEmpty() } ?: "示例文本"
            if (editable != null) {
                return json("type", mapOf("byText" to (editable.text.ifEmpty { "" }), "text" to text))
            }
        }

        // 5) 有明确可点击项但没匹配上目标：点第一个
        clickable.firstOrNull()?.let { return json("tap", mapOf("byText" to it.text)) }

        // 6) 无可执行动作 → 结束
        return json("done", mapOf("result" to "未在界面发现可执行目标，已结束。"))
    }

    private fun json(action: String, fields: Map<String, String>): String {
        val f = fields.entries.joinToString(", ") { "\"${it.key}\":\"${it.value}\"" }
        return """{"action":"$action"${if (f.isNotEmpty()) ",$f" else ""}}"""
    }

    private data class Node(val text: String, val desc: String, val clickable: Boolean, val editable: Boolean)

    private fun parseNodes(prompt: String): List<Node> {
        val inUi = prompt.indexOf("当前界面：")
        if (inUi < 0) return emptyList()
        val body = prompt.substring(inUi)
        val re = Regex("""text="([^"]*)"\s+desc="([^"]*)"\s+clickable=(\w+)\s+editable=(\w+)""")
        return re.findAll(body).map { m ->
            Node(
                text = m.groupValues[1],
                desc = m.groupValues[2],
                clickable = m.groupValues[3] == "true",
                editable = m.groupValues[4] == "true",
            )
        }.toList()
    }
}
