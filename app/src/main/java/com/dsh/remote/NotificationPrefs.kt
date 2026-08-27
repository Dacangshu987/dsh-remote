package com.dsh.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted notification-toggling preferences. Each field controls whether
 * the corresponding event triggers a system notification (only when the app
 * is not in the foreground).
 */
object NotificationPrefs {

    private const val PREFS = "notification_prefs"
    private const val KEY_TURN_END = "turn_end"
    private const val KEY_APPROVAL = "approval"
    private const val KEY_STALL = "stall"
    private const val KEY_TURN_START = "turn_start"
    private const val KEY_OFFLINE = "offline"

    data class Settings(
        val turnEnd: Boolean = true,     // 模型输出完毕
        val approval: Boolean = true,    // 需要用户回答
        val stall: Boolean = true,       // 输出意外暂停
        val turnStart: Boolean = false,  // 会话开始输出
        val offline: Boolean = true,     // 主机离线/恢复
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            turnEnd = p.getBoolean(KEY_TURN_END, true),
            approval = p.getBoolean(KEY_APPROVAL, true),
            stall = p.getBoolean(KEY_STALL, true),
            turnStart = p.getBoolean(KEY_TURN_START, false),
            offline = p.getBoolean(KEY_OFFLINE, true),
        )
    }

    fun save(context: Context, s: Settings) {
        prefs(context).edit()
            .putBoolean(KEY_TURN_END, s.turnEnd)
            .putBoolean(KEY_APPROVAL, s.approval)
            .putBoolean(KEY_STALL, s.stall)
            .putBoolean(KEY_TURN_START, s.turnStart)
            .putBoolean(KEY_OFFLINE, s.offline)
            .apply()
    }
}