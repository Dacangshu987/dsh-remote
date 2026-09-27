package com.dsh.remote

import android.content.Context
import org.json.JSONObject

/**
 * Turns the gateway's forwarded host events into user-facing notifications.
 *
 * The event allowlist this reads is the harness's own
 * `API_REMOTE_FORWARDED_EVENTS`; the two that matter here are plain `emit`
 * broadcasts, so this class is a strictly read-only subscriber:
 *
 *  - `api-session/status(sessionId, running)` — edge-triggered "a turn started
 *    running / stopped running". `true -> false` is the "agent finished" signal.
 *  - `api-session/error(sessionId, message)` — a live failure with no turn.
 *  - `api-session/added(summary)` — carries the session title, cached locally so
 *    notifications can name the conversation instead of a raw id.
 *  - `approval/request` / `user-questions/request` are **waterfall** events: they
 *    ask the client to answer. This class reports them as "needs you" and never
 *    answers, which leaves the host's own default behaviour intact.
 */
class AgentWatch(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val notifications = NotificationHelper(appContext)

    /** Sessions currently reported as running, plus when they last started. */
    private val running = mutableMapOf<String, Long>()

    /** sessionId -> last time an error / "needs you" notification fired. */
    private val lastNotifiedAt = mutableMapOf<String, Long>()

    /**
     * Session ids we have put an alert on screen for.
     *
     * Kept in memory (and mirrored to disk) because the notifications cannot be
     * found any other way: `NotificationManager.activeNotifications` is
     * documented to return an empty list on API 31+ — it reads `/proc`, which
     * apps can no longer see — verified returning zero on Android 12, so
     * clearing by enumeration silently does nothing on a modern phone. The
     * notification id is the session id's hash, so this set is enough to cancel
     * them exactly.
     */
    private val alertedSessions = mutableSetOf<String>()

    /** Ids of every alert currently on screen, for cancellation. */
    fun alertedNotificationIds(): List<Int> = alertedSessions.map { it.hashCode() }

    /** Forget the tracked alerts after they have been cancelled. */
    fun forgetAlerts() {
        alertedSessions.clear()
    }

    companion object {
        private const val PREFS = "dsh_remote_watch"
        private const val KEY_RUNNING = "running_sessions"
        private const val KEY_TITLES = "session_titles"
        private const val KEY_ALERTED = "alerted_sessions"
        private const val MAX_TITLES = 80
        private const val ERROR_COOLDOWN_MS = 120_000L
        private const val NEEDS_YOU_COOLDOWN_MS = 60_000L
        private const val NEEDS_APPROVAL = "approval"
        private const val NEEDS_ANSWER = "question"

        /**
         * Notification ids of alerts still on screen, read straight from disk.
         *
         * A static read on purpose: `AgentWatch` keeps its set in memory, and a
         * freshly constructed instance loads the file during construction —
         * which is a snapshot from before a *different* instance in the same
         * process posted anything. The app-foreground cleanup path runs
         * exactly in that situation, so it must not depend on instance state.
         */
        fun pendingAlertIds(context: Context): List<Int> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ALERTED, null) ?: return emptyList()
            return try {
                val json = JSONObject(raw)
                json.keys().asSequence().map { it.hashCode() }.toList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun loadPersistedAlerts() {
        val raw = prefs.getString(KEY_ALERTED, null) ?: return
        try {
            val json = JSONObject(raw)
            // Union, not replace: the persisted set is the source of truth for
            // alerts raised by *other* instances in this process, and clobbering
            // in-memory entries would lose alerts this instance just posted.
            for (key in json.keys()) alertedSessions.add(key)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_ALERTED).apply()
        }
    }

    private fun persistAlerted() {
        val json = JSONObject()
        for (id in alertedSessions) json.put(id, true)
        prefs.edit().putString(KEY_ALERTED, json.toString()).apply()
    }

    /** Raw JSON of the running map, so the watermark survives a restart. */
    private fun loadPersistedRunning() {
        val raw = prefs.getString(KEY_RUNNING, null) ?: return
        try {
            val json = JSONObject(raw)
            for (key in json.keys()) running[key] = json.optLong(key)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_RUNNING).apply()
        }
    }

    private fun persistRunning() {
        val json = JSONObject()
        for ((id, at) in running) json.put(id, at)
        prefs.edit().putString(KEY_RUNNING, json.toString()).apply()
    }

    private fun titleOf(sessionId: String): String {
        val raw = prefs.getString(KEY_TITLES, null) ?: return fallbackTitle(sessionId)
        return try {
            val json = JSONObject(raw)
            val stored = json.optString(sessionId)
            if (stored.isNullOrBlank()) fallbackTitle(sessionId) else stored
        } catch (_: Exception) {
            fallbackTitle(sessionId)
        }
    }

    private fun rememberTitle(sessionId: String, title: String) {
        if (title.isBlank()) return
        val json = try {
            JSONObject(prefs.getString(KEY_TITLES, null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        json.put(sessionId, title)
        // Bound the cache: notification titles are not worth unbounded growth.
        if (json.length() > MAX_TITLES) {
            // Keep only the most recent slice so the cache stays bounded.
            val keys = ArrayList<String>()
            val iterator = json.keys()
            while (iterator.hasNext()) keys.add(iterator.next())
            val trimmed = JSONObject()
            for (key in keys.takeLast(MAX_TITLES / 2)) trimmed.put(key, json.optString(key))
            prefs.edit().putString(KEY_TITLES, trimmed.toString()).apply()
        } else {
            prefs.edit().putString(KEY_TITLES, json.toString()).apply()
        }
    }

    private fun fallbackTitle(sessionId: String): String =
        "会话 " + sessionId.take(6)

    /**
     * Handle one forwarded `emit` event.
     *
     * @param event - the harness event name.
     * @param args - the raw argument array exactly as forwarded.
     */
    fun onEvent(event: String, args: List<Any?>) {
        when (event) {
            "api-session/status" -> onStatus(args)
            "api-session/error" -> onError(args)
            "api-session/added" -> onAdded(args)
            "api-session/removed" -> (args.firstOrNull() as? String)?.let { sessionId ->
                running.remove(sessionId)
                persistRunning()
            }
            "approval/request" -> onNeedsYou(NEEDS_APPROVAL)
            "user-questions/request" -> onNeedsYou(NEEDS_ANSWER)
        }
    }

    private fun onStatus(args: List<Any?>) {
        val sessionId = args.getOrNull(0) as? String ?: return
        val nowRunning = args.getOrNull(1) as? Boolean ?: return
        val now = System.currentTimeMillis()

        val wasRunning = running.containsKey(sessionId)
        if (nowRunning) {
            running[sessionId] = now
        } else {
            running.remove(sessionId)
        }
        persistRunning()

        // Only the true -> false edge, and only when the session was genuinely
        // busy — a session marked stopped that never ran is just bookkeeping.
        if (wasRunning && !nowRunning) {
            alertedSessions.add(sessionId)
            persistAlerted()
            notifications.notifyTurnFinished(sessionId, titleOf(sessionId))
        }
    }

    private fun onError(args: List<Any?>) {
        val sessionId = args.getOrNull(0) as? String ?: return
        val message = args.getOrNull(1) as? String ?: return
        if (!debounce(sessionId, ERROR_COOLDOWN_MS)) return
        // The error alert uses the session id plus one.
        alertedSessions.add(sessionId)
        persistAlerted()
        notifications.notifyError(sessionId, titleOf(sessionId), message)
    }

    private fun onAdded(args: List<Any?>) {
        val summary = args.firstOrNull() as? JSONObject ?: return
        val sessionId = summary.optString("id").ifBlank {
            summary.optString("sessionId")
        }
        if (sessionId.isBlank()) return
        val title = summary.optString("title").ifBlank { summary.optString("name") }
        rememberTitle(sessionId, title)
    }

    private fun onNeedsYou(kind: String) {
        if (!debounce(kind, NEEDS_YOU_COOLDOWN_MS)) return
        notifications.notifyNeedsYou(kind)
    }

    /** Keep one noisy signal from re-firing inside its cooldown window. */
    private fun debounce(key: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastNotifiedAt[key] ?: 0L
        if (now - previous < cooldownMs) return false
        lastNotifiedAt[key] = now
        return true
    }

    /** Called once per (re)connection so the map starts from a known state. */
    fun reset() {
        running.clear()
        lastNotifiedAt.clear()
        persistRunning()
    }

    init {
        loadPersistedRunning()
        loadPersistedAlerts()
    }
}
