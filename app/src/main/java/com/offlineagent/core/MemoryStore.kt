package com.offlineagent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地 App 知识库（记忆）：把探索过程中发现的 UI 元素持久化到应用私有目录。
 *
 * 对标 AppAgent 的核心理念——智能体先"探索/观察"某个 App，把"哪些元素可交互、
 * 各自大概干什么"记成知识库，后续执行同类任务时直接参考，减少对模型的盲目试探，
 * 从而显著提升离线小模型下的任务成功率。所有数据只存在本机，不上传。
 */
@Serializable
data class ElementRecord(
    val text: String,
    var clicks: Int = 0,
    var lastSeen: Long = 0L,
)

@Serializable
data class AppMemoryData(
    @SerialName("elements") val elements: Map<String, ElementRecord> = emptyMap(),
)

@Serializable
data class MemoryFile(
    @SerialName("version") val version: Int = 1,
    @SerialName("apps") val apps: Map<String, AppMemoryData> = emptyMap(),
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

    /** 一次成功操作：强化该元素的权重（点击次数 +1）。 */
    fun remember(packageName: String, elementText: String) {
        if (elementText.isBlank()) return
        synchronized(lock) {
            val apps = data.apps.toMutableMap()
            val cur = apps[packageName]?.elements?.toMutableMap() ?: mutableMapOf()
            val rec = cur[elementText] ?: ElementRecord(elementText)
            rec.clicks += 1
            rec.lastSeen = System.currentTimeMillis()
            cur[elementText] = rec
            apps[packageName] = AppMemoryData(cur)
            data = MemoryFile(apps = apps)
            save()
        }
    }

    /** 探索阶段：登记界面上出现过的可交互文本（不增加权重，仅记录"见过"）。 */
    fun discover(packageName: String, texts: List<String>) {
        val filtered = texts.filter { it.isNotBlank() }.distinct()
        if (filtered.isEmpty()) return
        synchronized(lock) {
            val apps = data.apps.toMutableMap()
            val cur = apps[packageName]?.elements?.toMutableMap() ?: mutableMapOf()
            val now = System.currentTimeMillis()
            for (t in filtered) {
                val rec = cur[t] ?: ElementRecord(t).apply { lastSeen = now }
                cur[t] = rec
            }
            apps[packageName] = AppMemoryData(cur)
            data = MemoryFile(apps = apps)
            save()
        }
    }

    /** 返回某应用已知可交互元素（按点击次数降序），供规划器参考。 */
    fun knownElements(packageName: String, limit: Int = 24): List<String> =
        synchronized(lock) {
            data.apps[packageName]?.elements?.values
                ?.sortedByDescending { it.clicks }
                ?.map { it.text }?.take(limit) ?: emptyList()
        }

    fun stats(): String = synchronized(lock) {
        val apps = data.apps.size
        val total = data.apps.values.sumOf { it.elements.size }
        "已知应用 $apps 个 · 累计元素 $total 条"
    }

    fun clear() = synchronized(lock) { data = MemoryFile(); save() }
}
