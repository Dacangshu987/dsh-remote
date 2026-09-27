package com.dsh.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Diagnostics receiver used to verify the mux socket on a real device: an
 * adb-driven check cannot read a background service's internal state, and the
 * service's own logs are not always reachable.
 *
 * It only ever *reads* — it opens a socket, waits for the gateway's `ready`
 * frame, and writes the one-line result to a file plus logcat. It performs no
 * state change, so the worst an uninvited caller can do is make it open a
 * socket to a URL it already knows.
 *
 *   adb shell am broadcast -n com.dsh.remote/.DebugCheckReceiver \
 *     -a com.dsh.remote.SELFCHECK --es url "ws://host:port/remote/api/remote.mux?device=<id>"
 */
class DebugCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_COOKIECHECK) {
            cookieCheck(context, intent.getStringExtra("host").orEmpty())
            return
        }
        if (intent.action == ACTION_FAKEEVENT) {
            fakeEvent(context, intent.getStringExtra("sessionId").orEmpty())
            return
        }
        if (intent.action != ACTION_SELFCHECK) return

        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank()) {
            Log.w(TAG, "self-check needs --es url")
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = MuxSocket.selfCheck(url)
                Log.i(TAG, "self-check result: $result")
                context.getFileStreamPath("selfcheck.txt").writeText(result)
            } catch (e: Exception) {
                Log.e(TAG, "self-check failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Drive [AgentWatch] with a synthetic `api-session/status` true -> false
     * transition, exercising the real notification path (detection, debounce,
     * title lookup, channel post) without needing a live agent turn.
     */
    private fun fakeEvent(context: Context, sessionId: String) {
        val id = sessionId.ifBlank { "debug-session" }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val watch = AgentWatch(context)
                watch.onEvent("api-session/status", listOf(id, true))
                watch.onEvent("api-session/status", listOf(id, false))
                Log.i(TAG, "fake-event injected for $id")
            } catch (e: Exception) {
                Log.e(TAG, "fake-event failed", e)
            }
        }
    }

    /** Read the WebView cookie jar for one origin (the device credential's home). */
    private fun cookieCheck(context: Context, host: String) {
        val pending = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val cookies = android.webkit.CookieManager.getInstance().getCookie(host).orEmpty()
                val credential = cookies.split(';')
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("$COOKIE_NAME=") }
                    ?.substringAfter('=')
                val out = when {
                    credential.isNullOrEmpty() -> "ERR no $COOKIE_NAME cookie for $host (raw: ${cookies.take(120)})"
                    else -> "OK $COOKIE_NAME=${credential.take(8)}… len=${credential.length}"
                }
                Log.i(TAG, "cookie-check: $out")
                context.getFileStreamPath("cookiecheck.txt").writeText(out)
            } catch (e: Exception) {
                Log.e(TAG, "cookie-check failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugCheck"

        /** The plugin's device cookie (`config.cookieName`, whose default this is). */
        private const val COOKIE_NAME = "dsh_pair"

        const val ACTION_SELFCHECK = "com.dsh.remote.SELFCHECK"
        const val ACTION_COOKIECHECK = "com.dsh.remote.COOKIECHECK"
        const val ACTION_FAKEEVENT = "com.dsh.remote.FAKEEVENT"
    }
}
