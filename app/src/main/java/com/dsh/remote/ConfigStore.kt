package com.dsh.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted app configuration: the DSH host base URL, the target machine MAC
 * address (for Wake-on-LAN), and the LAN broadcast address. Stored in
 * [SharedPreferences] so it survives process restarts.
 */
data class AppConfig(
    val host: String,
    val mac: String,
    val broadcast: String,
)

object ConfigStore {
    private const val PREFS = "dsh_remote_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_MAC = "mac"
    private const val KEY_BROADCAST = "broadcast"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppConfig {
        val p = prefs(context)
        return AppConfig(
            host = p.getString(KEY_HOST, "") ?: "",
            mac = p.getString(KEY_MAC, "") ?: "",
            broadcast = p.getString(KEY_BROADCAST, "") ?: "",
        )
    }

    fun save(context: Context, config: AppConfig) {
        prefs(context).edit()
            .putString(KEY_HOST, config.host.trim())
            .putString(KEY_MAC, config.mac.trim())
            .putString(KEY_BROADCAST, config.broadcast.trim())
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
