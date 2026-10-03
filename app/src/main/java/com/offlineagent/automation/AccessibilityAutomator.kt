package com.offlineagent.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.offlineagent.core.Action
import com.offlineagent.core.ActionResult
import com.offlineagent.core.DeviceController
import com.offlineagent.core.UiSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 基于无障碍服务的真机执行器，实现 [DeviceController]。
 *
 * 关键能力：
 * - 读取当前窗口（[UiSnapshot.fromRoot]）；
 * - 通过 [android.accessibilityservice.AccessibilityService.dispatchGesture] 派发点击/滑动；
 * - 通过 performAction 直接设置输入框文本（无需调起软键盘）；
 * - 通过 performGlobalAction 执行返回/桌面，以及启动指定应用。
 *
 * 注意：必须先在系统无障碍设置中开启本服务，[AgentAccessibilityService.instance] 才非空。
 */
class AccessibilityAutomator : DeviceController {

    private val service: AgentAccessibilityService?
        get() = AgentAccessibilityService.instance

    override suspend fun snapshot(): UiSnapshot {
        val svc = service ?: return UiSnapshot("unknown", null, emptyList())
        val root = svc.root() ?: return UiSnapshot("unknown", null, emptyList())
        val snap = UiSnapshot.fromRoot(root)
        root.recycle()
        return snap
    }

    override suspend fun execute(action: Action): ActionResult {
        val svc = service ?: return ActionResult(
            false,
            "无障碍服务未开启，无法执行。请到系统设置开启「离线智能体·自动化服务」。",
        )
        return when (action) {
            is Action.Tap -> dispatchTap(action.x.toFloat(), action.y.toFloat()).asResult("点击(${action.x},${action.y})")
            is Action.TapText -> tapByText(action.byText, svc)
            is Action.Type -> typeText(action.byText, action.text, svc)
            is Action.Swipe -> dispatchSwipe(action.fromX, action.fromY, action.toX, action.toY)
                .asResult("滑动")
            is Action.OpenApp -> openApp(action.packageName, svc)
            is Action.GoHome -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                .asResult("返回桌面")
            is Action.Back -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                .asResult("返回")
            is Action.Wait -> {
                delay(action.ms.toLong().coerceAtLeast(0))
                ActionResult(true, "等待 ${action.ms}ms")
            }
            is Action.Scroll -> scroll(action.direction, svc)
            is Action.TapGrid -> {
                val (w, h) = screenSize()
                val x = ((action.col + 0.5f) / Action.GRID_COLS * w).toInt().coerceIn(0, w)
                val y = ((action.row + 0.5f) / Action.GRID_ROWS * h).toInt().coerceIn(0, h)
                dispatchTap(x.toFloat(), y.toFloat()).asResult("网格点按(${action.row},${action.col})→($x,$y)")
            }
            is Action.Done -> ActionResult(true, "完成")
            is Action.Ask -> ActionResult(true, "需要用户澄清")
        }
    }

    // ---- 具体动作实现 ----

    private fun tapByText(byText: String, svc: AgentAccessibilityService): ActionResult {
        val node = findNode(svc) { it.text?.toString() == byText || it.contentDescription?.toString() == byText }
            ?: findNode(svc) { contains(it.text, byText) || contains(it.contentDescription, byText) }
            ?: return ActionResult(false, "未找到可点击节点：\'$byText\'")
        val r = Rect()
        node.getBoundsInScreen(r)
        node.recycle()
        val ok = dispatchTap(r.centerX().toFloat(), r.centerY().toFloat())
        return ok.asResult("点击文本=\"$byText\"")
    }

    private fun typeText(byText: String?, text: String, svc: AgentAccessibilityService): ActionResult {
        val node = if (byText != null) {
            findNode(svc) { it.isEditable && (it.text?.toString() == byText || contains(it.text, byText)) }
                ?: findNode(svc) { it.isEditable && contains(it.contentDescription, byText) }
        } else {
            findNode(svc) { it.isEditable }
        } ?: return ActionResult(false, "未找到可输入节点")
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        node.recycle()
        return ok.asResult("输入\"$text\"")
    }

    private fun openApp(pkg: String, svc: AgentAccessibilityService): ActionResult {
        val ctx = svc.applicationContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ActionResult(false, "未安装应用：$pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { return ActionResult(false, "启动失败：${it.message}") }
        return ActionResult(true, "已启动 $pkg")
    }

    private fun scroll(direction: String, svc: AgentAccessibilityService): ActionResult {
        val node = findNode(svc) { it.isScrollable }
            ?: return ActionResult(false, "当前界面无可滚动节点")
        val backward = direction in setOf("up", "left")
        val ok = node.performAction(
            if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        )
        node.recycle()
        return ok.asResult("滚动 $direction")
    }

    // ---- 手势派发 ----

    /** 返回屏幕尺寸（像素），用于网格坐标换算。 */
    private fun screenSize(): Pair<Int, Int> {
        val dm = service?.resources?.displayMetrics ?: return 1080 to 1920
        return dm.widthPixels to dm.heightPixels
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(desc)
    }

    private suspend fun dispatchSwipe(fx: Int, fy: Int, tx: Int, ty: Int): Boolean {
        val path = Path().apply { moveTo(fx.toFloat(), fy.toFloat()); lineTo(tx.toFloat(), ty.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(desc)
    }

    private suspend fun dispatchGestureAwait(desc: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureDescription.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = service?.dispatchGesture(desc, callback, null) ?: false
            if (!dispatched) cont.resume(false)
        }

    // ---- 节点查找工具 ----

    /** 广度优先遍历当前窗口，返回首个满足 [predicate] 的节点（调用方需 recycle）。 */
    private fun findNode(
        svc: AgentAccessibilityService,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val root = svc.root() ?: return null
        val all = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val collected = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<Long>()

        while (all.isNotEmpty()) {
            val node = all.removeFirst()
            val key = System.identityHashCode(node).toLong()
            if (!seen.add(key)) {
                node.recycle()
                continue
            }
            collected.add(node)
            repeat(node.childCount) { all.add(node.getChild(it)) }
        }

        var found: AccessibilityNodeInfo? = null
        for (n in collected) {
            if (found == null && predicate(n)) found = n else n.recycle()
        }
        return found
    }

    private fun contains(charSeq: CharSequence?, sub: String): Boolean =
        charSeq?.toString()?.contains(sub) == true

    private fun Boolean.asResult(desc: String): ActionResult =
        ActionResult(this, if (this) "已$desc" else "$desc 失败")
}
