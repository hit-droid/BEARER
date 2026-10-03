package com.offlineagent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一次录制中的一个步骤：执行了什么动作、执行前/后分别处于哪个页面、是否成功。
 *
 * 页面信息（[pageBefore]/[pageAfter]）来自 [UiSnapshot.pageKey]，回放时用于
 * 一致性校验与"页面漂移"诊断——若回放时当前页面与录制时不符，可提示用户手动介入。
 */
@Serializable
data class ScriptStep(
    @SerialName("action") val action: ScriptAction,
    @SerialName("pageBefore") val pageBefore: String = "",
    @SerialName("pageAfter") val pageAfter: String? = null,
    @SerialName("success") val success: Boolean = true,
)

/**
 * 可回放的离线自动化脚本：把一次成功的"规划→执行"过程固化下来，
 * 下次直接按步骤执行，**无需重新规划**，速度更快、确定性更高（对标 AppAgent 的演示学习）。
 */
@Serializable
data class ActionScript(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("goal") val goal: String,
    @SerialName("packageName") val packageName: String = "",
    @SerialName("createdAt") val createdAt: Long = 0,
    @SerialName("steps") val steps: List<ScriptStep>,
) {
    /** 脚本的简短人类可读摘要，用于列表展示。 */
    fun summary(): String =
        "「$name」· ${steps.size} 步 · ${packageName.ifEmpty { "多应用" }}"
}
