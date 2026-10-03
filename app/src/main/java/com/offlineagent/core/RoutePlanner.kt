package com.offlineagent.core

/**
 * 确定性路线规划器：**完全不用大模型**，直接从本地知识库的导航图里算出路线。
 *
 * ## 为什么需要它
 * 之前即便积累了"点 A → 去 B 页"的导航知识，规划时仍要把整份地图塞进 prompt，
 * 指望本地小模型现场推理出"该先点哪个、再点哪个"——这对 1B 级模型并不可靠。
 *
 * 这里改为**确定性解法**：把导航图当真正的图，跑 BFS 最短路径。命中则直接产出
 * 一串"依次点击哪些元素"，不消耗一次推理、不受采样随机性影响，成功率与速度都更优。
 *
 * ## 策略（知识优先、LLM 兜底）
 * 1. 先从目标里抽出关键词，在当前应用的导航图上搜索命中页面；
 * 2. 命中 → 返回一串点击步骤，由 [AgentLoop] 按步执行；
 * 3. 当前应用没有 → 检查**其它已探索应用**里是否有更匹配的页面，若有则先切换应用；
 * 4. 都没命中 → 返回 null，交回 LLM 规划（并可在 UI 提示"建议先探索"）。
 */
class RoutePlanner(private val memory: MemoryStore) {

    /** 一条确定性路线。 */
    data class Route(
        /** 依次要点击的元素文本（原始大小写，可直接用于 [Action.TapText]）。 */
        val steps: List<String>,
        /** 路线终点页面指纹。 */
        val targetPage: String,
        /** 路线终点页面标题（用于日志展示）。 */
        val targetTitle: String,
        /** 目标疑似位于其它应用时，指示先切换到该应用（此时 [steps] 为空）。 */
        val switchToApp: String? = null,
    ) {
        fun describe(): String = when {
            switchToApp != null -> "切换到应用 $switchToApp"
            steps.isEmpty() -> "已到达「${targetTitle.ifBlank { targetPage }}」"
            else -> "经 ${steps.size} 步抵达「${targetTitle.ifBlank { targetPage }}」：${steps.joinToString(" → ")}"
        }
    }

    /**
     * 规划一条通往目标的确定性路线。
     * @return 可用路线；知识库不足以规划时返回 null（应回退到 LLM 规划）
     */
    fun plan(goal: String, packageName: String, currentPage: String): Route? {
        val words = keywords(goal)
        if (words.isEmpty()) return null

        // ---- 1) 当前应用内的路径 ----
        val localLabels = preloadLabels(packageName)
        val path = memory.route(packageName, currentPage) { page ->
            match(page.title, page.key, localLabels[page.key].orEmpty(), words)
        }
        if (path != null) {
            val steps = path.map { memory.labelOf(packageName, it.elementKey) }
                .filter { it.isNotBlank() }
            val targetKey = path.lastOrNull()?.toPage ?: currentPage
            val targetTitle = titleOf(packageName, targetKey)
            // 零步路线（已在目标页）不产生可执行动作，交给 LLM 处理后续细节
            return if (steps.isEmpty()) Route(emptyList(), targetKey, targetTitle) else Route(steps, targetKey, targetTitle)
        }

        // ---- 2) 目标可能在另一个已探索过的应用 ----
        val best = memory.knownPackages()
            .filter { it != packageName }
            .mapNotNull { pkg ->
                val labels = preloadLabels(pkg)
                val hit = memory.pagesOf(pkg)
                    .filter { page -> match(page.title, page.key, labels[page.key].orEmpty(), words) }
                    .maxByOrNull { page ->
                        words.count { w -> (page.title + labels[page.key].orEmpty().joinToString()).contains(w, true) }
                    }
                if (hit == null) null else pkg to hit.title.ifBlank { hit.key }
            }
            .maxByOrNull { it.second.length }   // 取最具体（标题最长）的候选，降低误切换概率
        if (best != null) {
            return Route(emptyList(), "", best.second, switchToApp = best.first)
        }

        return null
    }

    /** 预取某应用所有页面的元素标签，避免搜索过程中反复加锁查询。 */
    private fun preloadLabels(packageName: String): Map<String, List<String>> =
        memory.pagesOf(packageName).associate { page -> page.key to memory.labelsOfPage(packageName, page.key) }

    private fun titleOf(packageName: String, pageKey: String): String =
        memory.pagesOf(packageName).firstOrNull { it.key == pageKey }?.title.orEmpty()

    /**
     * 页面是否匹配目标：标题、页面指纹或该页任一元素标签命中关键词即可。
     * 要求关键词**全部匹配或任一强匹配**：这里采用"任一关键词命中"的宽策略，
     * 配合 BFS 就近原则，实际表现更稳，同时避免过严导致零命中。
     */
    private fun match(title: String, key: String, labels: List<String>, words: List<String>): Boolean {
        val hay = buildString {
            append(title).append(' ').append(key).append(' ')
            append(labels.joinToString(" "))
        }
        return words.any { hay.contains(it, ignoreCase = true) }
    }

    /**
     * 从自然语言中抽取目标关键词：剔除交互性停用词后取长度 ≥ 2 的片段。
     * 例："打开设置并进入关于手机" → ["设置", "关于手机"]
     */
    fun keywords(goal: String): List<String> {
        var s = goal.trim()
        STOP_WORDS.forEach { s = s.replace(it, " ") }
        return s.split(DELIMITERS)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(6)
    }

    companion object {
        /** 交互意图词，不代表目标页面本身。 */
        private val STOP_WORDS = listOf(
            "打开", "进入", "查看", "帮我", "我想", "想要", "麻烦", "请", "然后", "接着", "之后",
            "并且", "再来", "一下", "这里", "里面", "手机", "开始", "执行", "完成", "点击", "操作",
        )

        private val DELIMITERS = Regex("[\\s，。、,.:：;；!！?？()（）\"'“”\\[\\]]+")
    }
}
