package com.offlineagent.core

import com.offlineagent.llm.ElementAnnotator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 自动探索器：自主遍历目标 App 的页面，把结果沉淀成文档型知识库。
 *
 * 对标 [Tencent AppAgent](https://github.com/TencentQQGYLab/AppAgent) 的第一阶段
 * 「Exploration」——它靠人工/随机演示走过 App 的各个页面来积累 UI 知识；
 * 我们把它做成**全自动、纯离线**的版本：广度优先地遍历 [UiSnapshot] 可达的页面，
 * 登记每个页面包含的元素与"点某元素→到达某页"的导航边。
 * 之后再把元素清单交给本地 LLM 做语义标注，完成第二阶段的知识文档生成。
 *
 * 与 [AgentLoop] 的区别：[AgentLoop] 是「带着目标去办事」，[Explorer] 是「先摸清地形」，
 * 不追求完成任务，只追求**把 App 的结构学全**，从而提升后续所有任务的成功率。
 *
 * ## 安全约束（重要）
 * 探索会真实点击屏幕，因此做了严格限制：
 * 1. **只点击，永不输入**：不产生任何文本或修改；
 * 2. **危险动作黑名单**：命中"卸载/删除/支付/登出"等标签的元素一律跳过；
 * 3. **限域探索**：只在目标包名范围内活动，一旦漂移到其它应用立刻返回；
 * 4. **步数/页面双上限**：到点即停，不会无限遍历。
 */
class Explorer(
    private val device: DeviceController,
    private val memory: MemoryStore,
    private val annotator: ElementAnnotator? = null,
) {

    data class Config(
        /** 最大探索步数（含点击、滚动、返回）。 */
        val maxSteps: Int = 40,
        /** 最多登记的页面数，达到即停止。 */
        val maxPages: Int = 12,
        /** 每步之后的等待，给界面留出加载时间。 */
        val stepDelayMs: Long = 700,
        /** 每页允许的滚动次数（用于发现折叠内容中的新元素）。 */
        val scrollsPerPage: Int = 1,
        /** 是否在探索结束后调用本地 LLM 给元素补语义描述。 */
        val annotate: Boolean = true,
    )

    sealed interface Event {
        /** 首次抵达某页面。 */
        data class Visiting(val pageKey: String, val title: String, val discovered: Int) : Event

        /** 尝试点击某元素以探索新跳转。 */
        data class Probing(val label: String) : Event

        /** 发现一条新导航：点击 [via] 后从 [from] 到 [to]。 */
        data class Navigation(val from: String, val via: String, val to: String) : Event

        /** 当前页无可探索元素，尝试返回上一页。 */
        data class Backtracking(val pageKey: String) : Event

        /** 语义标注完成。 */
        data class Annotated(val count: Int) : Event

        /** 探索结束。 */
        data class Finished(val pages: Int, val elements: Int, val steps: Int) : Event

        data class Error(val message: String) : Event
    }

    fun explore(targetPackage: String = "", config: Config = Config()): Flow<Event> = flow {
        val first = runCatching { device.snapshot() }.getOrElse { e ->
            emit(Event.Error("无法读取当前界面：${e.message}"))
            return@flow
        }
        val target = targetPackage.ifBlank { first.packageName }
        if (target.isBlank() || target == "unknown") {
            emit(Event.Error("无法确定目标应用，请先打开要探索的 App。"))
            return@flow
        }

        val visitedPages = LinkedHashSet<String>()
        val probedKeys = HashSet<String>()
        val scrollBudget = HashMap<String, Int>()
        var steps = 0
        var strayCount = 0

        while (steps < config.maxSteps) {
            if (visitedPages.size >= config.maxPages) break

            val snap = runCatching { device.snapshot() }.getOrElse { e ->
                emit(Event.Error("读取界面失败：${e.message}"))
                return@flow
            }

            // 限域：漂移到目标应用外，立即返回
            if (snap.packageName != target) {
                strayCount++
                // 连续多次回不到目标应用说明探索已越界，跳出并走统一收尾（统计 + 标注）
                if (strayCount > 3) break
                device.execute(Action.Back)
                steps++
                delay(config.stepDelayMs)
                continue
            }
            strayCount = 0

            // 登记当前页面与元素
            val isNewPage = visitedPages.add(snap.pageKey)
            registerPage(snap)
            if (isNewPage) {
                emit(Event.Visiting(snap.pageKey, snap.title, visitedPages.size))
            }

            // 挑选本页尚未探索过的安全元素
            val candidates = pick(snap, probedKeys)
            if (candidates.isNotEmpty()) {
                val probe = candidates.first()
                probedKeys.add(probe.key)
                emit(Event.Probing(probe.label))

                val beforeKey = snap.pageKey
                val res = runCatching { device.execute(Action.TapText(probe.label)) }
                    .getOrDefault(ActionResult(false, "执行异常"))
                steps++

                if (res.success) {
                    memory.success(target, probe.key)
                    delay(config.stepDelayMs)
                    val after = runCatching { device.snapshot() }.getOrNull()

                    if (after != null && after.packageName == target && after.pageKey != beforeKey) {
                        // 点击后进入了新页面 → 记一条导航边（离线场景下最宝贵的知识）
                        memory.recordNavigation(target, beforeKey, probe.key, after.pageKey)
                        registerPage(after)
                        emit(Event.Navigation(beforeKey, probe.label, after.pageKey))

                        // 广探原则：若跳进的是已探索过的页面，原路退回，不在此重复绕圈
                        if (visitedPages.contains(after.pageKey)) {
                            device.execute(Action.Back)
                            steps++
                        }
                    }
                }
                memory.flush()
                delay(config.stepDelayMs)
                continue
            }

            // 本页无可探索元素：先尝试滚动，发现隐藏候选
            val budget = scrollBudget[snap.pageKey] ?: config.scrollsPerPage
            if (budget > 0) {
                scrollBudget[snap.pageKey] = budget - 1
                device.execute(Action.Scroll("down"))
                steps++
                delay(config.stepDelayMs)
                val afterScroll = runCatching { device.snapshot() }.getOrNull()
                if (afterScroll != null && afterScroll.packageName == target && afterScroll.pageKey != snap.pageKey) {
                    registerPage(afterScroll)
                }
                continue
            }

            // 再无可探：返回上一页；退回不到新页面就结束
            emit(Event.Backtracking(snap.pageKey))
            device.execute(Action.Back)
            steps++
            delay(config.stepDelayMs)
            val back = runCatching { device.snapshot() }.getOrNull()
            if (back == null || back.packageName != target || back.pageKey == snap.pageKey) {
                break
            }
        }

        val app = memory.describePackageStats(target)
        memory.flush()

        // 第二阶段：用本地 LLM 把元素清单升级为带功能描述的知识文档
        if (config.annotate && annotator != null) {
            val written = annotateElements(target, annotator)
            emit(Event.Annotated(written))
        }

        emit(
            Event.Finished(
                pages = app.first,
                elements = app.second,
                steps = steps,
            ),
        )
    }

    /** 把一次快照中的页面与元素登记进知识库。 */
    private fun registerPage(snap: UiSnapshot) {
        val pkg = snap.packageName
        val visible = snap.nodes.filter { it.clickable || it.editable || it.scrollable }
        val keys = ArrayList<String>(visible.size)
        for (n in visible) {
            val key = elementKeyOf(n.text, n.desc)
            keys.add(key)
            val role = inferRole(n.className, n.editable, n.clickable)
            val fn = inferFunction(n.text, n.desc, n.editable, role)
            memory.recordElement(pkg, key, n.text, n.desc, fn, role, snap.pageKey)
        }
        memory.recordPage(pkg, snap.pageKey, snap.title, keys)
    }

    /** 一次探索点击的候选：元素 key + 用于 [Action.TapText] 的标签。 */
    private data class Probe(val key: String, val label: String)

    private fun pick(snap: UiSnapshot, probed: Set<String>): List<Probe> =
        snap.nodes
            .filter { it.clickable && !it.scrollable && !it.editable }
            .mapNotNull { n ->
                val key = elementKeyOf(n.text, n.desc)
                val label = n.text.ifBlank { n.desc }.trim()
                when {
                    label.isBlank() -> null
                    !isSafe(label) -> null
                    probed.contains(key) -> null
                    else -> Probe(key, label)
                }
            }

    private suspend fun annotateElements(pkg: String, annotator: ElementAnnotator): Int {
        if (!annotator.available) return 0
        val specs = memory.pendingSpecs(pkg, limit = 40)
        if (specs.isEmpty()) return 0

        val functions = LinkedHashMap<String, String>()
        // 分批请求：批次小、输出短，本地小模型才稳得住
        specs.chunked(8).forEach { batch ->
            val got = annotator.annotate(
                appLabel = pkg,
                pageTitle = batch.firstOrNull()?.pageTitle.orEmpty(),
                specs = batch.map { ElementAnnotator.Spec(it.text, it.desc, it.role) },
            )
            got.forEach { (i, fn) -> batch.getOrNull(i)?.let { functions[it.key] = fn } }
        }
        return memory.applyFunctions(pkg, functions)
    }

    companion object {
        /** 探索时绝不触碰的标签：可能造成不可逆后果或资金风险。 */
        private val DANGEROUS = listOf(
            "删除", "卸载", "停用", "清除", "清空", "格式化", "恢复出厂", "重置",
            "注销", "退出登录", "登出", "解绑", "注销账号",
            "支付", "付款", "转账", "充值", "订阅", "购买", "下单",
            "举报", "拉黑", "屏蔽",
        )

        /** 标签最长长度：过长的通常是内容正文而非导航控件。 */
        private const val MAX_LABEL = 16

        fun isSafe(label: String): Boolean =
            label.length <= MAX_LABEL && DANGEROUS.none { label.contains(it) }
    }
}
