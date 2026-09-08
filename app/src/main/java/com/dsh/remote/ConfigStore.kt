package com.dsh.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted app configuration: the DSH host base URL. Stored in
 * [SharedPreferences] so it survives process restarts.
 */
data class AppConfig(
    val host: String,
)

object ConfigStore {
    private const val PREFS = "dsh_remote_prefs"
    private const val KEY_HOST = "host"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppConfig {
        val p = prefs(context)
        return AppConfig(
            host = p.getString(KEY_HOST, "") ?: "",
        )
    }

    fun save(context: Context, config: AppConfig) {
        prefs(context).edit()
            .putString(KEY_HOST, config.host.trim())
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
