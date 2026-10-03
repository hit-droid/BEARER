package com.offlineagent.di

import android.content.Context
import android.content.SharedPreferences

/**
 * 简单的本地设置存储（SharedPreferences）。
 * 离线优先：模型文件放在应用私有目录，路径在此配置。
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("offline_agent", Context.MODE_PRIVATE)

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MODEL_PATH, v).apply()

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMP, 0.4f)
        set(v) = prefs.edit().putFloat(KEY_TEMP, v).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 256)
        set(v) = prefs.edit().putInt(KEY_MAX_TOKENS, v).apply()

    var automationEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO, v).apply()

    /** 网格点按模式：在主页叠加网格，便于参考单元格坐标下达 tap_grid 动作。 */
    var gridMode: Boolean
        get() = prefs.getBoolean(KEY_GRID, false)
        set(v) = prefs.edit().putBoolean(KEY_GRID, v).apply()

    /**
     * 知识优先：执行任务时先在已积累的导航图上做确定性路径搜索，
     * 命中则直接按路线走（不调用大模型）；查不到路径再回退到本地 LLM 规划。
     */
    var routeFirst: Boolean
        get() = prefs.getBoolean(KEY_ROUTE, true)
        set(v) = prefs.edit().putBoolean(KEY_ROUTE, v).apply()

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_TEMP = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_AUTO = "automation_enabled"
        private const val KEY_GRID = "grid_mode"
        private const val KEY_ROUTE = "route_first"
    }
}
