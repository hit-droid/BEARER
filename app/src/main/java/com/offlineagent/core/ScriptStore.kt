package com.offlineagent.core

import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地脚本库：把录制得到的 [ActionScript] 以 JSON 形式持久化到应用私有目录
 * （[dir]/<id>.json）。完全离线，不依赖任何云同步。
 */
class ScriptStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 保存（覆盖同名 id）。 */
    fun save(script: ActionScript) {
        runCatching { File(dir, "${script.id}.json").writeText(json.encodeToString(script)) }
    }

    /** 列出全部脚本（按创建时间倒序）。 */
    fun list(): List<ActionScript> {
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<ActionScript>(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** 按 id 读取单个脚本。 */
    fun get(id: String): ActionScript? =
        runCatching { json.decodeFromString<ActionScript>(File(dir, "$id.json").readText()) }.getOrNull()

    /** 删除指定脚本。 */
    fun delete(id: String): Boolean = File(dir, "$id.json").delete()

    fun count(): Int = dir.listFiles { f -> f.extension == "json" }?.size ?: 0
}
