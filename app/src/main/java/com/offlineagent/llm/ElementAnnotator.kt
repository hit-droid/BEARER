package com.offlineagent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.toList

/**
 * UI 元素语义标注器：把"元素清单"变成"带功能描述的文档"。
 *
 * 对标 [Tencent AppAgent](https://github.com/TencentQQGYLab/AppAgent) 的
 * 「自动生成 element documentation」阶段——它用 GPT-4V 看截图写元素说明，
 * 我们则用**本地模型 + 无障碍文本信息**离线完成同一件事：
 * 给探索到的每个元素生成一句简短中文说明（如"查看设备信息"），写入知识库，
 * 让后续规划时模型能理解"点这个会发生什么"，而不是只看到一个字符串标签。
 *
 * 无可用本地模型（如仅 [StubEngine]）时 [available] 为 false，调用方应回退到
 * 启发式推断，保证探索流程在纯规则模式下依然可用。
 */
class ElementAnnotator(private val engine: LlmEngine) {

    /** 待标注元素：只携带模型判断所需的必要字段。 */
    data class Spec(
        val text: String,
        val desc: String,
        val role: String,
    )

    @Serializable
    private data class Item(
        @SerialName("i") val i: Int = -1,
        @SerialName("function") val function: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 当前引擎是否支持标注任务。
     * 规则规划器（[StubEngine]）只能输出动作 JSON，无法遵循任意指令，必须降级。
     */
    val available: Boolean
        get() = engine.canFollowArbitraryInstructions && engine.isModelLoaded

    /**
     * 批量生成元素功能描述。
     *
     * @param appLabel 应用可读标识（包名）
     * @param pageTitle 元素所在页面标题
     * @param specs 待标注元素，顺序敏感
     * @return 下标 → 功能描述；解析失败或未启用时返回空 map（调用方回退启发式）
     */
    suspend fun annotate(
        appLabel: String,
        pageTitle: String,
        specs: List<Spec>,
    ): Map<Int, String> {
        if (specs.isEmpty() || !available) return emptyMap()

        val system = buildSystemPrompt()
        val prompt = buildUserPrompt(appLabel, pageTitle, specs)

        val raw = runCatching {
            engine.generate(
                system = system,
                prompt = prompt,
                params = GenParams(
                    temperature = 0.1f,
                    maxTokens = (specs.size * 24 + 96).coerceAtMost(1024),
                    stop = listOf("\n\n", "用户：", "<|im_end|>"),
                ),
            ).toList().joinToString("")
        }.getOrElse { return emptyMap() }

        return parseIndexed(raw, specs.size)
    }

    private fun buildSystemPrompt(): String = """
你是安卓界面元素分析专家。你的任务：根据用户给出的某个 App 页面上的控件标签，
用简短中文说明每个控件的作用，帮助智能体之后正确地操作它。

要求：
1. 只输出一个 JSON 数组，不要任何解释、不要 Markdown 代码块。
2. 数组每项形如 {"i": 下标, "function": "用途说明"}。
3. function 不超过 12 个汉字，动词开头，例如："搜索内容"、"查看 WLAN 列表"、"切换蓝牙开关"。
4. 只依据标签字面含义与常见安卓交互习惯推断，不要编造不存在的具体功能。
5. i 必须严格对应输入元素的下标。

示例输出：
[{"i":0,"function":"搜索设置项"},{"i":1,"function":"查看 WLAN 列表"}]
""".trimIndent()

    private fun buildUserPrompt(appLabel: String, pageTitle: String, specs: List<Spec>): String {
        val body = specs.mapIndexed { i, s ->
            "- i=$i | text=\"${s.text.replace("\"", "'")}\" | desc=\"${s.desc.replace("\"", "'")}\" | role=${s.role}"
        }.joinToString("\n")
        return """
应用：$appLabel
页面：$pageTitle
元素列表：
$body
""".trimIndent()
    }

    /**
     * 从模型输出中稳健地提取 JSON 数组：取首个 '[' 到末个 ']' 之间的片段，
     * 容忍模型前后附加的解释文字。任何异常都安全返回空 map。
     */
    private fun parseIndexed(raw: String, size: Int): Map<Int, String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()

        val items = runCatching {
            json.decodeFromString<List<Item>>(raw.substring(start, end + 1))
        }.getOrElse { return emptyMap() }

        val out = LinkedHashMap<Int, String>()
        for (it in items) {
            val fn = it.function.trim().take(24)
            if (it.i in 0 until size && fn.isNotEmpty()) out[it.i] = fn
        }
        return out
    }
}
