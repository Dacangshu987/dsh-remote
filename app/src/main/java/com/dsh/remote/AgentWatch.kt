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
            notifications.notifyTurnFinished(sessionId, titleOf(sessionId))
        }
    }

    private fun onError(args: List<Any?>) {
        val sessionId = args.getOrNull(0) as? String ?: return
        val message = args.getOrNull(1) as? String ?: return
        if (!debounce(sessionId, ERROR_COOLDOWN_MS)) return
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
    }

    private companion object {
        const val PREFS = "dsh_remote_watch"
        const val KEY_RUNNING = "running_sessions"
        const val KEY_TITLES = "session_titles"
        const val MAX_TITLES = 80
        const val ERROR_COOLDOWN_MS = 120_000L
        const val NEEDS_YOU_COOLDOWN_MS = 60_000L
        const val NEEDS_APPROVAL = "approval"
        const val NEEDS_ANSWER = "question"
    }
}
