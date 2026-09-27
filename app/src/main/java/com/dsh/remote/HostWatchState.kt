package com.dsh.remote

import android.content.Context

/**
 * The one fact the home-screen widget needs: whether the watcher currently
 * holds a live connection to the host. Written by [HostWatchService] and read
 * by [WatchWidgetProvider]; a separate object keeps the widget from having to
 * bind or start the service just to render.
 */
object HostWatchState {

    private const val PREFS = "dsh_remote_watch_state"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_UPDATED_AT = "updated_at"

    fun setConnected(context: Context, connected: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONNECTED, connected)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONNECTED, false)

    fun updatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED_AT, 0L)
}
