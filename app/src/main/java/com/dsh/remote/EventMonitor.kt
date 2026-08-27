package com.dsh.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background event monitor: reads the mobile SSE stream (events.mux) and
 * checks the host heartbeat. Fires system notifications when the app is
 * not in the foreground, according to the user's NotificationPrefs.
 *
 * Lifecycle is bound to its caller (MainActivity): start/stop pairs must
 * be called on the same thread; the reader threads self-terminate on stop.
 */
class EventMonitor(private val context: Context, private val host: String) {

    companion object {
        private const val CHANNEL_ID = "dsh_remote_events"
        private const val CHANNEL_NAME = "DSH Remote 通知"

        private const val NOTIFY_TURN_END = 1001
        private const val NOTIFY_APPROVAL = 1002
        private const val NOTIFY_STALL = 1003
        private const val NOTIFY_TURN_START = 1004
        private const val NOTIFY_OFFLINE = 1005
        private const val NOTIFY_ONLINE = 1006

        private const val SSE_RECONNECT_BASE_MS = 3_000L
        private const val SSE_RECONNECT_MAX_MS = 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val OFFLINE_THRESHOLD = 3
    }

    private val prefs = NotificationPrefs.load(context)
    private var stopped = false
    private var isRunning = false
    private var wasOffline = false
    private var offlineCount = 0
    private var sseReconnectDelay = SSE_RECONNECT_BASE_MS
    private var sseThread: Thread? = null
    private var heartbeatThread: Thread? = null
    /** The last sessionId seen in a turn/end frame, used for navigation. */
    private var lastSessionId: String? = null

    /** Whether the app UI is currently visible (set by MainActivity). */
    @Volatile var isForeground = true

    /** Create the notification channel on first use (API 26+). */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "DSH Remote 会话事件与主机状态通知"
        }
        nm.createNotificationChannel(ch)
    }

    /** Start both SSE reader and heartbeat check. */
    fun start() {
        ensureChannel()
        stopped = false
        startSse()
        startHeartbeat()
    }

    /** Stop all threads. Idempotent. */
    fun stop() {
        stopped = true
        sseThread = null
        heartbeatThread = null
    }

    /* ── SSE reader ──────────────────────────────────────────────────── */

    private fun startSse() {
        if (stopped) return
        sseThread = Thread { runSse() }.apply { isDaemon = true; start() }
    }

    private fun runSse() {
        while (!stopped) {
            var conn: HttpURLConnection? = null
            try {
                val base = host.trim().trimEnd('/')
                val url = "$base/m/api/events.mux"
                val cookie = CookieManager.getInstance().getCookie(url)

                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 0 // no read timeout — SSE never ends
                if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("Accept", "text/event-stream")

                sseReconnectDelay = SSE_RECONNECT_BASE_MS // reset on successful connect

                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                var line: String? = null
                while (!stopped && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("data: ")) {
                        parseSseFrame(l.substring(6))
                    }
                }
            } catch (_: Exception) {
                // connection dropped — retry
            } finally {
                conn?.disconnect()
            }
            if (stopped) break
            sleep(sseReconnectDelay)
            sseReconnectDelay = (sseReconnectDelay * 2).coerceAtMost(SSE_RECONNECT_MAX_MS)
        }
    }

    private fun parseSseFrame(data: String) {
        try {
            val obj = JSONObject(data)
            // Each frame is a server-request envelope; the payload is the mux frame.
            val payload = obj.optJSONObject("payload") ?: return
            val type = payload.optString("type", "")

            // session/event frames carry the actual event type inside event.type.
            if (type == "session/event") {
                val ev = payload.optJSONObject("event") ?: return
                val eventType = ev.optString("type", "")
                val sid = payload.optString("sessionId", "")

                if (eventType == "turn/start") {
                    isRunning = true
                    if (prefs.turnStart) notify(NOTIFY_TURN_START, "助手开始输出", "", null)
                }
                if (eventType == "turn/end") {
                    isRunning = false
                    lastSessionId = sid.ifEmpty { null }
                    if (prefs.turnEnd) notify(NOTIFY_TURN_END, "助手已回复", "新消息已就绪", lastSessionId)
                }
                return
            }

            // Direct-frame types (approval, question)
            val sid = payload.optString("sessionId", "").ifEmpty { null }
            if (type == "approval/requested" || type == "question/requested") {
                if (prefs.approval) notify(NOTIFY_APPROVAL, "需要你的确认", "模型等待你的回应", sid)
                return
            }
        } catch (_: Exception) {
            // malformed frame — skip
        }
    }

    /* ── Heartbeat (offline detection) ───────────────────────────────── */

    private fun startHeartbeat() {
        if (stopped) return
        heartbeatThread = Thread { runHeartbeat() }.apply { isDaemon = true; start() }
    }

    private fun runHeartbeat() {
        while (!stopped) {
            sleep(HEARTBEAT_INTERVAL_MS)
            if (stopped) break
            val ok = pingHost()
            if (ok) {
                offlineCount = 0
                if (wasOffline) {
                    wasOffline = false
                    if (prefs.offline) notify(NOTIFY_ONLINE, "主机已联网", "远程连接已恢复", null)
                }
            } else {
                offlineCount++
                if (offlineCount >= OFFLINE_THRESHOLD && !wasOffline) {
                    wasOffline = true
                    if (prefs.offline) notify(NOTIFY_OFFLINE, "主机已离线", "远程连接已断开", null)
                }
            }
        }
    }

    private fun pingHost(): Boolean {
        return try {
            val base = host.trim().trimEnd('/')
            val url = "$base/api/pair/status"
            val cookie = CookieManager.getInstance().getCookie(url)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /* ── Notification helpers ────────────────────────────────────────── */

    /**
     * Send a notification if the permission is granted (Android 13+).
     * Marks the app as notified so the user can tap the notification to
     * return to MainActivity.
     */
    private fun notify(id: Int, title: String, content: String, sessionId: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { /* wake up */ }
    }
}