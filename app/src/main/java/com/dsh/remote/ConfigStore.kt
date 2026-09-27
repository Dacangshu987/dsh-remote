package com.dsh.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted app configuration: the DSH host base URL plus one diagnostic
 * timestamp. Stored in [SharedPreferences] so it survives process restarts.
 *
 * [lastReachableAt] is 0L until the host's Web GUI has loaded at least once.
 */
data class AppConfig(
    val host: String,
    val lastReachableAt: Long = 0L,
)

object ConfigStore {
    private const val PREFS = "dsh_remote_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_LAST_REACHABLE = "last_reachable_at"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppConfig {
        val p = prefs(context)
        return AppConfig(
            host = p.getString(KEY_HOST, "") ?: "",
            lastReachableAt = p.getLong(KEY_LAST_REACHABLE, 0L),
        )
    }

    fun save(context: Context, config: AppConfig) {
        prefs(context).edit()
            .putString(KEY_HOST, config.host.trim())
            .apply()
    }

    /** Record that the host's GUI answered just now (drives the error page hint). */
    fun markReachable(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_REACHABLE, System.currentTimeMillis())
            .apply()
    }

    /**
     * Drop the stored host so the next launch enters pairing again.
     *
     * A saved host is what routes every later launch straight to the GUI, so
     * this is the only way back to the pairing screen once paired.
     */
    fun forget(context: Context) {
        prefs(context).edit()
            .remove(KEY_HOST)
            .remove(KEY_LAST_REACHABLE)
            .apply()
    }

    /** Normalize a host to `http(s)://host:port` without a trailing slash. */
    fun normalizeHost(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else trimmed
        return withScheme
    }
}
