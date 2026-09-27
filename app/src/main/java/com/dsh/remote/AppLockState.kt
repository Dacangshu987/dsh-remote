package com.dsh.remote

import android.app.Application
import android.content.Context
import android.os.SystemClock

/**
 * Whether the app should ask for the device credential before showing the
 * WebView.
 *
 * The paired device credential is a **full-control credential** for the host's
 * DSH (it can read the workspace, run tools, and change credentials), so a
 * briefly unattended phone is a real risk. This lock is opt-in and keeps a
 * foreground grace window so normal app switching is not interrupted.
 *
 * State lives in [Application] memory rather than on disk: a process restart is
 * exactly when the lock must apply again, and a plain timestamp is all the
 * process needs to remember.
 */
object AppLockState {

    private const val PREFS = "dsh_remote_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GRACE_MS = "grace_ms"

    /** Foreground grace window; beyond this the lock re-engages. */
    private const val DEFAULT_GRACE_MS = 2 * 60 * 1000L

    /** 0 = still locked, non-zero = the elapsed-realtime mark of the last unlock. */
    private var unlockedAtElapsed = 0L

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) lock()
    }

    fun lock() {
        unlockedAtElapsed = 0L
    }

    fun markUnlocked() {
        unlockedAtElapsed = SystemClock.elapsedRealtime()
    }

    /** True once the grace window has passed (or nothing unlocked this process). */
    fun shouldLock(context: Context): Boolean {
        if (!isEnabled(context)) return false
        if (unlockedAtElapsed == 0L) return true
        return SystemClock.elapsedRealtime() - unlockedAtElapsed > graceMs(context)
    }

    /** Called from [RemoteApp] so a fresh process always starts locked. */
    fun resetForNewProcess() {
        unlockedAtElapsed = 0L
    }

    private fun graceMs(context: Context): Long =
        prefs(context).getLong(KEY_GRACE_MS, DEFAULT_GRACE_MS)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
