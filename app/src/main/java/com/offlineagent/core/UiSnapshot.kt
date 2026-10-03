package com.offlineagent.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 当前屏幕的轻量结构化快照，用于喂给规划器（本地 LLM / Stub 规划器）。
 *
 * 不直接持有 [AccessibilityNodeInfo]，而是把需要的字段拷贝出来，避免节点引用泄漏
 * 与回收问题。大屏节点较多时仅保留可交互/有文本的节点，控制上下文长度。
 */
data class UiSnapshot(
    val packageName: String,
    val activity: String?,
    val nodes: List<Node>,
    /**
     * 页面指纹：由 `packageName + 界面显著文本集合` 计算，稳定区分不同页面。
     * 用于知识库中的"页面"识别与导航边记录（离线无截图，无法用语义理解页面，
     * 用结构化签名近似 AppAgent 的"页面锚点"）。
     */
    val pageKey: String = "unknown#none",
    /** 尽力提取的页面标题（界面顶部可读性较好的短文本），仅供展示与 prompt。 */
    val title: String = "",
) {
    /** 转为紧凑文本，作为规划器 prompt 的一部分。 */
    fun toPromptText(): String {
        if (nodes.isEmpty()) return "(界面为空或无可读节点)"
        val sb = StringBuilder()
        nodes.forEachIndexed { i, n ->
            val flags = buildString {
                if (n.clickable) append("clickable=true ")
                if (n.editable) append("editable=true ")
                if (n.scrollable) append("scrollable=true ")
            }.trim()
            val label = listOf(n.text, n.desc).firstOrNull { !it.isNullOrBlank() } ?: ""
            sb.append("${i + 1}. [${flags}] text=\"${n.text}\" desc=\"${n.desc}\"")
            if (n.bounds != null) sb.append(" bounds=[${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}]")
            sb.append('\n')
        }
        return sb.toString()
    }

    data class Node(
        val text: String,
        val desc: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val className: String?,
        val bounds: Rect?,
        val viewId: String?,
    )

    companion object {
        /** 从无障碍根节点构建快照。会安全遍历并仅保留有文本/可交互的节点。 */
        fun fromRoot(root: AccessibilityNodeInfo?): UiSnapshot {
            if (root == null) return UiSnapshot("unknown", null, emptyList())

            val collected = mutableListOf<Node>()
            val seen = HashSet<Long>()
            // 全部非空文本/描述（用于页面指纹）；短文本候选（用于尽力提取标题）
            val allTexts = mutableListOf<String>()
            val shortTexts = mutableListOf<String>()

            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                // 用窗口层级去重，避免重复收集同一节点
                val key = System.identityHashCode(node).toLong()
                if (!seen.add(key)) return

                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                if (t.isNotBlank()) {
                    allTexts.add(t)
                    if (t.length in 1..30) shortTexts.add(t)
                } else if (d.isNotBlank()) {
                    allTexts.add(d)
                }

                val hasText = t.isNotBlank()
                val hasDesc = d.isNotBlank()
                val interactive = node.isClickable || node.isEditable || node.isScrollable
                if (hasText || hasDesc || interactive) {
                    val r = Rect()
                    try {
                        node.getBoundsInScreen(r)
                    } catch (_: Exception) {
                        r.setEmpty()
                    }
                    collected.add(
                        Node(
                            text = t,
                            desc = d,
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            scrollable = node.isScrollable,
                            className = node.className?.toString(),
                            bounds = if (r.isEmpty) null else Rect(r),
                            viewId = node.viewIdResourceName,
                        )
                    )
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    visit(node.getChild(i))
                }
            }
            visit(root)

            // 优先保留可交互 + 有文本的节点，裁剪到上限，降低小模型负担
            val trimmed = collected
                .sortedWith(compareByDescending<Node> { it.clickable || it.editable }
                    .thenByDescending { it.text.isNotBlank() })
                .take(MAX_NODES)

            val pkg = root.packageName?.toString().orEmpty()
            val act = root.className?.toString()

            // 页面标题：优先取不含数字/符号、像标题的短文本
            val title = shortTexts
                .firstOrNull { it.any { c -> c.isLetter() } }
                ?: shortTexts.firstOrNull().orEmpty()
            // 页面指纹：显著文本集合签名，稳定区分页面
            val signature = allTexts.distinct().sorted().take(60).joinToString("|")
            val pageKey = "$pkg#${signature.hashCode().absoluteValue.toString(36)}"

            return UiSnapshot(pkg, act, trimmed, pageKey, title.take(48))
        }

        private const val MAX_NODES = 60
    }
}
