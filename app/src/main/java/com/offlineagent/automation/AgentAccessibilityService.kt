package com.offlineagent.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 离线智能体的无障碍自动化服务。
 *
 * 它由用户在系统「设置 → 无障碍」中手动开启后，[instance] 会被赋值，
 * [AccessibilityAutomator] 借此读取屏幕内容、派发手势、执行全局动作（返回/桌面）。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本智能体采用主动拉取（snapshot）方式读取界面，无需常驻监听事件。
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 当前活动窗口的根节点；调用方负责 recycle。 */
    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}
