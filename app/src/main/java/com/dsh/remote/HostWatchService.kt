package com.dsh.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Long-lived foreground service that keeps one mux socket to the DSH host and
 * turns hosted events into notifications.
 *
 * It does three jobs, ordered by how much they lean on undocumented behaviour:
 *
 *  1. **Presence heartbeat** (`POST /api/pair/heartbeat`, documented). Without
 *     it the desktop panel shows this phone as offline, and the host's pet
 *     reappears, while the app is merely backgrounded.
 *  2. **Transport watchdog** (`GET /api/pair/status`, documented): host
 *     reachable/unreachable, tunnel and relay failures.
 *  3. **Forwarded events** (`$events` over `/remote/api/remote.mux`, an
 *     *internal* gateway protocol): `api-session/status` running true -> false is
 *     the "agent finished" signal. If that protocol ever changes the service
 *     degrades to jobs 1 and 2 instead of breaking.
 *
 * The subscriber is strictly read-only: it never answers the host's `waterfall`
 * requests, which leaves the harness's own default handling untouched.
 */
class HostWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val notifications by lazy { NotificationHelper(this) }
    private val watch by lazy { AgentWatch(this) }

    private var muxJob: Job? = null
    private var pollJob: Job? = null

    /** Reachability from the two independent signals we own. */
    private var socketConnected = false
    private var httpReachable: Boolean? = null
    private var lastAnnounced: Boolean? = null

    /** The state currently shown in the foreground notice, so it updates once. */
    private var announcedState: String? = null

    private var lastTunnelFailed = false
    private var lastRelayFailed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(getString(R.string.watch_starting))
        if (muxJob == null) startMuxLoop()
        if (pollJob == null) startPollLoop()
        // Restart with a null intent after a process kill: keep watching.
        return START_STICKY
    }

    private fun startInForeground(text: String) {
        // A missing channel makes startForeground throw
        // CannotPostForegroundServiceNotificationException and kill the whole
        // process, so make sure it exists right here instead of trusting setup
        // that happened earlier in the lifecycle.
        notifications.ensureChannels()

        val notification = notifications.buildWatchNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.ID_WATCH,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.ID_WATCH, notification)
        }
    }

    /**
     * Replace the foreground notification's text with the current state.
     *
     * Two things this deliberately does **not** do any more:
     *  - It does not bail out when notifications are disabled. The permission
     *    governs *posting* a notification; updating one that `startForeground`
     *    already posted does not need it, and the old guard meant a denied
     *    permission left this notice frozen on "connecting" forever.
     *  - It does not re-post a state that is already showing, so a reconnect
     *    loop cannot spam the notification shade.
     */
    private fun setState(state: String, textRes: Int) {
        if (state == announcedState) return
        announcedState = state
        val text = getString(textRes)
        main.post { postWatchNotification(text) }
    }

    private fun postWatchNotification(text: String) {
        try {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NotificationHelper.ID_WATCH, notifications.buildWatchNotification(text))
        } catch (e: Exception) {
            // Updating the foreground notice must never take the service down.
            Log.w(TAG, "cannot update watch notification", e)
        }
    }

    /* ── Job 3: forwarded events over the mux socket ─────────────────── */

    private fun startMuxLoop() {
        muxJob = scope.launch {
            var backoffMs = 2_000L
            var firstAttempt = true
            while (isActive) {
                val host = ConfigStore.normalizeHost(ConfigStore.load(this@HostWatchService).host)
                val credential = SecretStore.credential(this@HostWatchService)
                if (host == null || credential == null) {
                    setState(STATE_WAITING_CREDENTIAL, R.string.watch_waiting_credential)
                    delay(RETRY_WITHOUT_CREDENTIAL_MS)
                    continue
                }
                // Only the first attempt reads as "connecting"; later ones are
                // reconnects, which is what the user is actually watching.
                if (!firstAttempt) setState(STATE_RECONNECTING, R.string.watch_reconnecting)
                firstAttempt = false
                try {
                    readEvents(host, credential)
                    backoffMs = 2_000L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "mux disconnected: ${e.message}")
                } finally {
                    socketConnected = false
                    announceReachability()
                }
                setState(STATE_RECONNECTING, R.string.watch_reconnecting)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    /**
     * Connect, open the `$events` stream, and pump frames until the socket dies.
     * Returns normally on a clean close; throws when the transport fails.
     */
    private suspend fun readEvents(host: String, credential: String) = withContext(Dispatchers.IO) {
        val wsBase = when {
            host.startsWith("https://") -> host.replaceFirst("https://", "wss://")
            host.startsWith("http://") -> host.replaceFirst("http://", "ws://")
            else -> "ws://$host"
        }
        val url = "$wsBase/remote/api/remote.mux?device=${URLEncoder.encode(credential, "UTF-8")}"
        val session = MuxSocket.connect(url)

        val streamId = UUID.randomUUID().toString()
        // The gateway requires exactly {args:{}} for the forwarded-event stream.
        session.sendText(
            JSONObject()
                .put("type", "open")
                .put("streamId", streamId)
                .put("endpoint", "\$events")
                .put("payload", JSONObject().put("args", JSONObject()))
                .toString()
        )

        // A fresh generation starts with no known running sessions, so the first
        // true -> false edge after a reconnect can never be a phantom.
        watch.reset()
        try {
            while (true) {
                val text = session.readText() ?: continue
                handleFrame(text, streamId)
            }
        } finally {
            session.close()
        }
    }

    private fun handleFrame(text: String, streamId: String) {
        val frame = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        if (frame.optString("streamId") != streamId) return

        when (frame.optString("type")) {
            "item" -> handleItem(frame.optJSONObject("value") ?: return)
            "error" -> Log.i(TAG, "stream error: ${frame.optJSONObject("error")}")
            "end" -> throw MuxSocket.ClosedException("stream ended by host")
        }
    }

    private fun handleItem(value: JSONObject) {
        when (value.optString("type")) {
            "ready" -> {
                socketConnected = true
                // announces the connected state to the foreground notice too.
                announceReachability()
            }
            "emit" -> {
                val event = value.optString("event")
                val args = jsonArrayToList(value.optJSONArray("args"))
                main.post { watch.onEvent(event, args) }
            }
            "waterfall" -> {
                // The host is asking the client to answer. We surface it and
                // deliberately leave the delivery outstanding, which is exactly
                // the host's "no client connected" behaviour — never a stall we
                // introduce, and never an answer we are not entitled to give.
                val event = value.optString("event")
                Log.i(TAG, "needs-you event (left for the GUI to answer): $event")
                main.post { watch.onEvent(event, emptyList()) }
            }
            "cancel" -> Unit
        }
    }

    /** JSON array -> list, preserving the raw values the events carry. */
    private fun jsonArrayToList(array: JSONArray?): List<Any?> {
        if (array == null) return emptyList()
        val out = ArrayList<Any?>(array.length())
        for (i in 0 until array.length()) out.add(array.opt(i))
        return out
    }

    /* ── Jobs 1 and 2: heartbeat, status, transport watchdog ─────────── */

    private fun startPollLoop() {
        pollJob = scope.launch {
            while (isActive) {
                val host = ConfigStore.normalizeHost(ConfigStore.load(this@HostWatchService).host)
                val credential = SecretStore.credential(this@HostWatchService)
                if (host == null) {
                    delay(RETRY_WITHOUT_CREDENTIAL_MS)
                    continue
                }
                if (credential != null) sendHeartbeat(host, credential)
                evaluateStatus(HostStatusClient.fetch(host))
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Presence heartbeat. The plugin's own phone client sends these every 10s;
     * without them the desktop panel shows this device as offline.
     *
     * The credential must arrive as the **device cookie** — measured against a
     * live host, the `x-dsh-remote-device` header is rejected with 401 here
     * (`gate.js` reads `readCookie(headers.cookie, config.cookieName)`), while
     * `Cookie: dsh_pair=<id>` returns 200 and flips the host's `onlineCount`.
     */
    private fun sendHeartbeat(host: String, credential: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$host/api/pair/heartbeat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Cookie", "$DEVICE_COOKIE=$credential")
                setRequestProperty("Content-Length", "0")
                setRequestProperty("User-Agent", "DSH-Remote-Android")
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            // 401 means this device credential is no longer accepted.
            if (conn.responseCode == 401) {
                SecretStore.clear(this@HostWatchService)
                main.post { notifications.notifyRevoked() }
            }
        } catch (_: Exception) {
            // Network blips are the status check's business, not this call's.
        } finally {
            conn?.disconnect()
        }
    }

    private fun evaluateStatus(status: HostStatusClient.HostStatus) {
        httpReachable = status.reachable
        announceReachability()

        val tunnelFailed = status.tunnelState == "failed"
        if (tunnelFailed && !lastTunnelFailed) {
            notifyTransport(KEY_TRANSPORT, R.string.notify_tunnel_title, R.string.notify_tunnel_text)
        }
        lastTunnelFailed = tunnelFailed

        val relayFailed = status.relayState == "failed"
        if (relayFailed && !lastRelayFailed) {
            notifyTransport(KEY_TRANSPORT, R.string.notify_relay_title, R.string.notify_relay_text)
        }
        lastRelayFailed = relayFailed
    }

    /**
     * Announce a reachability transition once, whichever signal moved first,
     * and reflect it in the foreground notice.
     */
    private fun announceReachability() {
        val reachable = socketConnected || httpReachable == true
        if (lastAnnounced == reachable) return
        val hadPrevious = lastAnnounced != null
        lastAnnounced = reachable
        HostWatchState.setConnected(this, reachable)
        WatchWidgetProvider.refresh(this)

        if (reachable) {
            setState(STATE_CONNECTED, R.string.watch_connected)
            return
        }
        // Unreachable: the foreground notice says so, and only a *change* into
        // unreachable raises an alert (the first poll must not alert).
        setState(STATE_HOST_OFFLINE, R.string.watch_host_offline)
        if (hadPrevious) {
            val title = getString(R.string.notify_host_offline_title)
            val text = getString(R.string.notify_host_offline_text)
            main.post { notifications.notifyTransport(KEY_HOST, title, text) }
        }
    }

    private fun notifyTransport(idKey: String, titleRes: Int, textRes: Int) {
        main.post {
            notifications.notifyTransport(idKey, getString(titleRes), getString(textRes))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HostWatchService"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val RETRY_WITHOUT_CREDENTIAL_MS = 30_000L
        private const val KEY_HOST = "host"
        private const val KEY_TRANSPORT = "transport"

        /** Foreground-notice states; one text each, shown once per transition. */
        private const val STATE_CONNECTED = "connected"
        private const val STATE_RECONNECTING = "reconnecting"
        private const val STATE_HOST_OFFLINE = "host-offline"
        private const val STATE_WAITING_CREDENTIAL = "waiting-credential"

        /**
         * The plugin's `cookieName`, whose default is `dsh_pair`. The field is
         * user-configurable host-side; a non-default name simply means the
         * heartbeat is ignored while the other signals keep working.
         */
        private const val DEVICE_COOKIE = "dsh_pair"

        const val ACTION_STOP = "com.dsh.remote.action.STOP_WATCH"

        fun start(context: Context) {
            val intent = Intent(context, HostWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostWatchService::class.java))
        }
    }
}
