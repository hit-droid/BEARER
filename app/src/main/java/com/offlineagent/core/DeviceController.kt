package com.offlineagent.core

/**
 * 设备控制抽象：智能体闭环与具体执行方式（无障碍服务、脚本回放等）解耦。
 * [AccessibilityAutomator] 是该接口在真机上的实现。
 */
interface DeviceController {

    /** 读取当前屏幕快照。 */
    suspend fun snapshot(): UiSnapshot

    /** 执行一个动作，返回是否成功与可读信息。 */
    suspend fun execute(action: Action): ActionResult
}

data class ActionResult(val success: Boolean, val message: String)
