package com.offlineagent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地 App 知识库（文档型记忆）：对标 AppAgent 的"探索 → 知识库"机制。
 *
 * 与早期"纯元素清单"（只记文本+点击数）不同，这里把探索发现的 UI 信息组织成
 * 一份**带功能描述的文档**：
 *  - 每个可交互元素记录 [ElementKnowledge]：它显示什么、大概能干什么（function）、常见所在页面、成功次数；
 *  - 每个页面记录 [PageKnowledge]：标题 + 它包含哪些元素；
 *  - 记录**导航边** [NavEdge]：在某页点击某元素后会到达哪个页面。
 *
 * 规划时把"当前应用的页面地图 + 导航边"整份注入 prompt，让本地小模型知道
 * "点「关于手机」会进入关于页"，从而大幅减少盲目试探，提升离线成功率。
 * 所有数据只存本机私有目录，绝不上传。
 */
@Serializable
data class ElementKnowledge(
    /** 稳定标识：由 text|desc 归一化得到。 */
    val key: String,
    val text: String,
    val desc: String,
    /** 功能描述（启发式生成 + 运行时可补充），如"查看设备信息"。 */
    val function: String,
    /** 元素角色：button / edittext / toggle / listitem / control。 */
    val role: String,
    /** 常见所在页面（pageKey）。 */
    var pageKey: String,
    var clicks: Int = 0,
    var success: Int = 0,
    var fail: Int = 0,
    var lastSeen: Long = 0L,
    /**
     * 功能描述的来源：
     * - "heuristic"：由 [inferFunction] 按规则猜得（粗糙，例如直接复用标签文本）；
     * - "llm"：由本地 LLM 通过 [com.offlineagent.llm.ElementAnnotator] 生成的语义描述。
     * 语义标注只会升级 heuristic → llm，不会覆盖已有的 llm 描述。
     */
    var source: String = SOURCE_HEURISTIC,
) {
    companion object {
        const val SOURCE_HEURISTIC = "heuristic"
        const val SOURCE_LLM = "llm"
    }
}

@Serializable
data class PageKnowledge(
    val key: String,
    val title: String,
    val packageName: String,
    val elementKeys: List<String>,
)

@Serializable
data class NavEdge(
    val fromPage: String,
    /** 触发导航的元素 key。 */
    val elementKey: String,
    val toPage: String,
    var visits: Int = 0,
)

@Serializable
data class AppKnowledgeDocument(
    @SerialName("package") val packageName: String,
    val pages: Map<String, PageKnowledge> = emptyMap(),
    val elements: Map<String, ElementKnowledge> = emptyMap(),
    val navEdges: List<NavEdge> = emptyList(),
)

@Serializable
data class MemoryFile(
    @SerialName("version") val version: Int = 2,
    @SerialName("apps") val apps: Map<String, AppKnowledgeDocument> = emptyMap(),
)

class MemoryStore(private val file: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()

    @Volatile
    private var data: MemoryFile = load()

    private fun load(): MemoryFile = runCatching {
        if (file.exists()) json.decodeFromString<MemoryFile>(file.readText()) else MemoryFile()
    }.getOrDefault(MemoryFile())

    private fun save() = runCatching { file.writeText(json.encodeToString(data)) }

    /**
     * 把内存中的知识落盘。探索登记等方法只更新内存（避免每步几十次全量写文件），
     * 由调用方在合适时机（如每步末尾）统一调用一次 [flush]。
     */
    fun flush() = save()

    /** 登记/更新一个页面及其包含的元素（探索阶段调用，不加权）。 */
    fun recordPage(
        packageName: String,
        pageKey: String,
        title: String,
        elementKeys: List<String>,
    ) {
        synchronized(lock) {
            val apps = data.apps.toMutableMap()
            val app = apps[packageName]?.copy() ?: AppKnowledgeDocument(packageName)
            val pages = app.pages.toMutableMap()
            val existing = pages[pageKey]
            val newKeys = ((existing?.elementKeys ?: emptyList()) + elementKeys).distinct()
            pages[pageKey] = PageKnowledge(
                key = pageKey,
                title = title.ifBlank { existing?.title ?: "" },
                packageName = packageName,
                elementKeys = newKeys,
            )
            apps[packageName] = app.copy(pages = pages)
            data = MemoryFile(apps = apps)
        }
    }

    /** 登记/更新一个元素（探索阶段调用，不加权）。 */
    fun recordElement(
        packageName: String,
        key: String,
        text: String,
        desc: String,
        function: String,
        role: String,
        pageKey: String,
    ) {
        synchronized(lock) {
            val apps = data.apps.toMutableMap()
            val app = apps[packageName]?.copy() ?: AppKnowledgeDocument(packageName)
            val elements = app.elements.toMutableMap()
            val now = System.currentTimeMillis()
            val cur = elements[key]
            elements[key] = if (cur != null) {
                cur.copy(
                    text = text.ifBlank { cur.text },
                    desc = desc.ifBlank { cur.desc },
                    function = function.ifBlank { cur.function },
                    role = role.ifBlank { cur.role },
                    pageKey = pageKey,
                    lastSeen = now,
                )
            } else {
                ElementKnowledge(
                    key = key, text = text, desc = desc, function = function,
                    role = role, pageKey = pageKey, lastSeen = now,
                )
            }
            apps[packageName] = app.copy(elements = elements)
            data = MemoryFile(apps = apps)
        }
    }

    /** 记录一次导航：在 fromPage 点击 elementKey 后到达 toPage。 */
    fun recordNavigation(packageName: String, fromPage: String, elementKey: String, toPage: String) {
        if (fromPage == toPage) return
        synchronized(lock) {
            val apps = data.apps.toMutableMap()
            val app = apps[packageName]?.copy() ?: AppKnowledgeDocument(packageName)
            val edges = app.navEdges.toMutableList()
            val idx = edges.indexOfFirst { it.fromPage == fromPage && it.elementKey == elementKey && it.toPage == toPage }
            if (idx >= 0) edges[idx] = edges[idx].copy(visits = edges[idx].visits + 1)
            else edges.add(NavEdge(fromPage, elementKey, toPage, 1))
            apps[packageName] = app.copy(navEdges = edges)
            data = MemoryFile(apps = apps)
        }
    }

    /** 标记某元素操作成功（加权），提升后续优先级。 */
    fun success(packageName: String, key: String) = bump(packageName, key, true)

    /** 标记某元素操作失败（降级），减少后续误用。 */
    fun fail(packageName: String, key: String) = bump(packageName, key, false)

    private fun bump(packageName: String, key: String, ok: Boolean) {
        if (key.isBlank()) return
        synchronized(lock) {
            val apps = data.apps.toMutableMap()
            val app = apps[packageName] ?: return
            val elements = app.elements.toMutableMap()
            val cur = elements[key] ?: return
            elements[key] = if (ok) cur.copy(success = cur.success + 1, clicks = cur.clicks + 1)
            else cur.copy(fail = cur.fail + 1, clicks = cur.clicks + 1)
            apps[packageName] = app.copy(elements = elements)
            data = MemoryFile(apps = apps)
        }
    }

    /**
     * 生成给规划器用的"页面地图 + 导航"可读文档。
     * 重点呈现：当前页面的已知元素（含功能描述与成功率）、从本页可直达的页面、
     * 以及其它已探索页面。供本地小模型参考，减少盲目试探。
     */
    fun describe(packageName: String, pageKey: String): String = synchronized(lock) {
        val app = data.apps[packageName] ?: return "(暂无该应用的本地知识)"
        val sb = StringBuilder()
        sb.appendLine("本地知识库（本应用，探索积累）：")

        // 当前页面的已知元素
        val page = app.pages[pageKey]
        val pageElements = page?.elementKeys
            ?.mapNotNull { app.elements[it] }
            ?.filter { it.text.isNotBlank() || it.desc.isNotBlank() }
            ?.sortedByDescending { it.success }
            ?: emptyList()
        sb.appendLine("· 当前页「${page?.title ?: pageKey}」已知可交互元素：")
        if (pageElements.isEmpty()) sb.appendLine("    (暂无，本次执行中会逐步探索)")
        pageElements.take(20).forEach { e ->
            val rate = if (e.clicks > 0) " 成功率${e.success}/${e.clicks}" else ""
            sb.appendLine("    - \"${e.text.ifBlank { e.desc }}\"：用于${e.function}（${e.role}$rate）")
        }

        // 从当前页面可直达的导航边
        val outEdges = app.navEdges.filter { it.fromPage == pageKey }.take(8)
        if (outEdges.isNotEmpty()) {
            sb.appendLine("· 从本页点击可直达：")
            outEdges.forEach { edge ->
                val el = app.elements[edge.elementKey]
                val label = el?.text?.ifBlank { el.desc } ?: edge.elementKey
                val dest = app.pages[edge.toPage]?.title ?: edge.toPage
                sb.appendLine("    - 点「$label」→「$dest」")
            }
        }

        // 其它已探索页面
        val otherPages = app.pages.values.filter { it.key != pageKey }.take(6)
        if (otherPages.isNotEmpty()) {
            sb.appendLine("· 其它已知页面：${otherPages.joinToString("、") { "「${it.title.ifBlank { it.key }}」" }}")
        }
        sb.toString().trimEnd()
    }

    /** 兼容旧调用：返回该应用已知元素展示文本（按成功率降序）。 */
    fun knownElements(packageName: String, limit: Int = 24): List<String> = synchronized(lock) {
        data.apps[packageName]?.elements?.values
            ?.sortedByDescending { it.success }
            ?.map { "\"${it.text.ifBlank { it.desc }}\"（${it.function}）" }
            ?.take(limit) ?: emptyList()
    }

    /** 由表单读取出的待标注元素，交给语义标注器生成功能描述。 */
    data class ElementSpec(
        val key: String,
        val text: String,
        val desc: String,
        val role: String,
        val pageTitle: String,
    )

    /**
     * 取出该应用尚未获得语义描述的元素，供本地 LLM 批量标注。
     * 已由 LLM 标注过的元素不再重复处理，控制成本。
     */
    fun pendingSpecs(packageName: String, limit: Int = 40): List<ElementSpec> = synchronized(lock) {
        val app = data.apps[packageName] ?: return emptyList()
        app.elements.values
            .filter { it.source != ElementKnowledge.SOURCE_LLM }
            .filter { it.text.isNotBlank() || it.desc.isNotBlank() }
            .sortedByDescending { it.clicks }
            .take(limit)
            .map {
                ElementSpec(
                    key = it.key,
                    text = it.text,
                    desc = it.desc,
                    role = it.role,
                    pageTitle = app.pages[it.pageKey]?.title.orEmpty(),
                )
            }
    }

    /**
     * 回写一批 LLM 生成的语义描述，并标记为 [ElementKnowledge.SOURCE_LLM]。
     * @param functions 元素 key → 功能描述
     * @return 实际写入的条数
     */
    fun applyFunctions(packageName: String, functions: Map<String, String>): Int {
        if (functions.isEmpty()) return 0
        var written = 0
        synchronized(lock) {
            val apps = data.apps.toMutableMap()
            val app = apps[packageName] ?: return 0
            val elements = app.elements.toMutableMap()
            for ((key, fn) in functions) {
                val cur = elements[key] ?: continue
                val desc = fn.trim().take(32)
                if (desc.isBlank()) continue
                elements[key] = cur.copy(function = desc, source = ElementKnowledge.SOURCE_LLM)
                written++
            }
            apps[packageName] = app.copy(elements = elements)
            data = MemoryFile(apps = apps)
        }
        if (written > 0) flush()
        return written
    }

    /** 某应用已积累的知识规模：页面数 → 元素数。 */
    fun describePackageStats(packageName: String): Pair<Int, Int> = synchronized(lock) {
        val app = data.apps[packageName] ?: return 0 to 0
        app.pages.size to app.elements.size
    }

    fun stats(): String = synchronized(lock) {
        val apps = data.apps.size
        val totalPages = data.apps.values.sumOf { it.pages.size }
        val totalElems = data.apps.values.sumOf { it.elements.size }
        val totalNav = data.apps.values.sumOf { it.navEdges.size }
        val annotated = data.apps.values.sumOf { app -> app.elements.values.count { it.source == ElementKnowledge.SOURCE_LLM } }
        "已知应用 $apps 个 · 页面 $totalPages · 元素 $totalElems（语义标注 $annotated） · 导航 $totalNav"
    }

    /** 导出全量可读知识库（供 UI 预览 / 调试），展示页面、元素功能与导航边。 */
    fun dump(): String = synchronized(lock) {
        if (data.apps.isEmpty()) return "（知识库为空，运行任务后会自动积累）"
        val sb = StringBuilder()
        data.apps.values.forEach { app ->
            sb.appendLine("应用 ${app.packageName}")
            app.pages.values.forEach { p ->
                sb.appendLine("  页面「${p.title.ifBlank { p.key }}」(${p.elementKeys.size} 元素)")
            }
            app.elements.values.sortedByDescending { it.success }.take(30).forEach { e ->
                val label = e.text.ifBlank { e.desc }
                val tag = if (e.source == ElementKnowledge.SOURCE_LLM) " [语义]" else ""
                sb.appendLine("    · \"$label\"：${e.function} [${e.role}]${tag} 成功${e.success}/${e.clicks}")
            }
            app.navEdges.take(20).forEach { edge ->
                val el = app.elements[edge.elementKey]
                val label = el?.text?.ifBlank { el.desc } ?: edge.elementKey
                val from = app.pages[edge.fromPage]?.title ?: edge.fromPage
                val to = app.pages[edge.toPage]?.title ?: edge.toPage
                sb.appendLine("    导航：点「$label」@「$from」→「$to」(${edge.visits})")
            }
            sb.appendLine()
        }
        sb.toString().trimEnd()
    }

    fun clear() = synchronized(lock) { data = MemoryFile(); save() }
}

/** 由文本/描述生成元素的稳定 key。 */
fun elementKeyOf(text: String, desc: String): String {
    val base = text.trim().ifBlank { desc.trim() }
    return if (base.isBlank()) "anon" else base.lowercase()
}

/** 由节点信息推断元素角色。 */
fun inferRole(className: String?, editable: Boolean, clickable: Boolean): String {
    val c = className?.substringAfterLast('.').orEmpty().lowercase()
    return when {
        editable || c.contains("edittext") -> "edittext"
        c.contains("switch") || c.contains("checkbox") -> "toggle"
        c.contains("button") -> "button"
        c.contains("imagebutton") -> "button"
        clickable && c.contains("textview") -> "listitem"
        clickable -> "control"
        else -> "control"
    }
}

/** 由节点信息推断元素的"功能描述"（启发式，无需多模态模型）。 */
fun inferFunction(
    text: String,
    desc: String,
    editable: Boolean,
    role: String,
): String {
    val label = text.ifBlank { desc }.trim()
    return when {
        editable -> "输入文本"
        role == "toggle" -> if (label.isNotEmpty()) "切换「$label」状态" else "切换开关"
        label.isNotEmpty() -> label
        else -> "可交互控件"
    }
}
