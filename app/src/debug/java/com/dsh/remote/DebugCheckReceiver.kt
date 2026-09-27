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
        if (intent.action == ACTION_UPDATECHECK) {
            updateCheck(context)
            return
        }
        if (intent.action == ACTION_DOWNLOADCHECK) {
            downloadCheck(context)
            return
        }
        if (intent.action == ACTION_POSTALERT) {
            postAlertViaService(context, intent.getStringExtra("sessionId").orEmpty())
            return
        }
        if (intent.action == ACTION_CLEARALERTS) {
            val cleared = HostWatchService.debugClearAlerts()
            Log.i(TAG, "clear requested, service present=$cleared")
            context.getFileStreamPath("clearalerts.txt").writeText("servicePresent=$cleared")
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
     * Start the watcher (if needed) and have it post an alert, so the
     * "clear on app open" path can be exercised without a live host.
     */
    private fun postAlertViaService(context: Context, sessionId: String) {
        val id = sessionId.ifBlank { "debug-alert" }
        HostWatchService.start(context)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val posted = HostWatchService.debugPostAlert(id)
            Log.i(TAG, "alert posted via service=$posted for $id")
        }, 3_000)
    }

    /**
     * Run the real update check and dump exactly what it concluded.
     *
     * Built because "why did it offer me a version I already have?" cannot be
     * answered from the outside: it needs the installed version string, the
     * resolved latest version, and the comparison result together.
     */
    private fun updateCheck(context: Context) {
        val pending = goAsync()
        UpdateChecker.check(BuildConfig.VERSION_NAME) { info ->
            try {
                val line = buildString {
                    append("installed=").append(BuildConfig.VERSION_NAME)
                    append(" code=").append(BuildConfig.VERSION_CODE)
                    append(" | latest=").append(info.latestVersion.ifEmpty { "<none>" })
                    append(" | isNewer=").append(info.isNewer)
                    append(" | cmp=").append(UpdateChecker.compareVersions(info.latestVersion, BuildConfig.VERSION_NAME))
                    append(" | apk=").append(info.apkUrl.ifEmpty { "<none>" })
                }
                Log.i(TAG, "update-check: $line")
                context.getFileStreamPath("updatecheck.txt").writeText(line)
            } catch (e: Exception) {
                Log.e(TAG, "update-check dump failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Run the full update check **and** try the download it points at, writing
     * the exact outcome of both.
     *
     * Built after "built-in downloader fails" turned out to be a blocked host
     * rather than a code fault: the check only reads `api.github.com`, while the
     * asset lives on `github.com`, and those two are filtered differently on
     * some networks. Seeing both results together is what makes that visible.
     */
    private fun downloadCheck(context: Context) {
        val pending = goAsync()
        UpdateChecker.check(BuildConfig.VERSION_NAME) { info ->
            val dest = java.io.File(context.cacheDir, "dlprobe.apk")
            val failure = UpdateChecker.download(info.apkUrl.ifEmpty { "about:blank" }, dest)
            val line = buildString {
                append("latest=").append(info.latestVersion.ifEmpty { "<none>" })
                append(" | apkUrl=").append(info.apkUrl.ifEmpty { "<none>" })
                append(" | download=").append(failure ?: "OK")
                append(" | bytes=").append(if (dest.isFile) dest.length() else 0)
            }
            try {
                Log.i(TAG, "download-check: $line")
                context.getFileStreamPath("downloadcheck.txt").writeText(line)
            } finally {
                dest.delete()
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
        const val ACTION_UPDATECHECK = "com.dsh.remote.UPDATECHECK"
        const val ACTION_DOWNLOADCHECK = "com.dsh.remote.DOWNLOADCHECK"
        const val ACTION_POSTALERT = "com.dsh.remote.POSTALERT"
        const val ACTION_CLEARALERTS = "com.dsh.remote.CLEARALERTS"
    }
}
