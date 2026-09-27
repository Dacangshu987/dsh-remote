package com.dsh.remote

import android.app.Application
import android.webkit.WebView

/** Process-scoped state; a fresh process always starts with the lock engaged. */
class RemoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLockState.resetForNewProcess()
        CrashLog.install(this)
        // WebView remote debugging is useful for driving the loaded GUI during
        // development and dangerous in a shipped build, so it is gated on the
        // build type rather than on a separate subclass.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
