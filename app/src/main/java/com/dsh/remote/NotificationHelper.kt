package com.dsh.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * One home for every notification this app posts.
 *
 * Two channels, deliberately different in weight:
 *  - `watch` — the foreground-service presence notice (low, silent, idle).
 *  - `alerts` — "the agent finished" / "the agent needs you" (default, sounds).
 */
class NotificationHelper(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Create both channels; safe to call on every service start.
     *
     * `startForeground` **crashes the process** when the referenced channel does
     * not exist (`CannotPostForegroundServiceNotificationException: Bad
     * notification for startForeground`), so this must succeed before the first
     * foreground post. Failures are logged rather than swallowed, because the
     * symptom otherwise appears far from the cause.
     */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (system == null) {
            Log.e(TAG, "no NotificationManager: the foreground notification cannot be posted")
            return
        }

        createIfMissing(
            system,
            CHANNEL_WATCH,
            R.string.channel_watch,
            R.string.channel_watch_desc,
            NotificationManager.IMPORTANCE_LOW,
        )
        createIfMissing(
            system,
            CHANNEL_ALERTS,
            R.string.channel_alerts,
            R.string.channel_alerts_desc,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    private fun createIfMissing(
        system: NotificationManager,
        id: String,
        nameRes: Int,
        descRes: Int,
        importance: Int,
    ) {
        if (system.getNotificationChannel(id) != null) return
        try {
            system.createNotificationChannel(
                NotificationChannel(id, context.getString(nameRes), importance).apply {
                    description = context.getString(descRes)
                }
            )
            Log.i(TAG, "created channel $id")
        } catch (e: Exception) {
            Log.e(TAG, "failed to create channel $id", e)
        }
    }

    /** Whether the user has granted (or not yet needed) notification permission. */
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return manager.areNotificationsEnabled()
    }

    /** The persistent foreground-service notice. */
    fun buildWatchNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            pendingFlags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notifyTurnFinished(sessionId: String, title: String) {
        post(
            id = sessionId.hashCode(),
            notification = baseBuilder(context.getString(R.string.notify_finished_title))
                .setContentText(context.getString(R.string.notify_finished_text, title))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.notify_finished_text, title))
                )
                .setContentIntent(openApp(sessionId))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun notifyError(sessionId: String, title: String, message: String) {
        post(
            id = sessionId.hashCode() + 1,
            notification = baseBuilder(context.getString(R.string.notify_error_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$message"))
                .setContentIntent(openApp(sessionId))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** "Needs you": approval or a question. Tapping opens the app, never answers. */
    fun notifyNeedsYou(kind: String) {
        val textRes = if (kind == KIND_APPROVAL) R.string.notify_approval_text else R.string.notify_question_text
        post(
            id = ID_NEEDS_YOU,
            notification = baseBuilder(context.getString(R.string.notify_needs_you_title))
                .setContentText(context.getString(textRes))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(textRes)))
                .setContentIntent(openApp(sessionId = null))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    /** Revoked pairing: actionable, so it says what to do next. */
    fun notifyRevoked() {
        post(
            id = ID_REVOKED,
            notification = baseBuilder(context.getString(R.string.notify_revoked_title))
                .setContentText(context.getString(R.string.notify_revoked_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notify_revoked_text)))
                .setContentIntent(openApp(sessionId = null))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Transport problem (tunnel / relay / host gone). */
    fun notifyTransport(idKey: String, title: String, text: String) {
        post(
            id = ID_TRANSPORT_BASE + (idKey.hashCode() and 0xFF),
            notification = baseBuilder(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(sessionId = null))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun baseBuilder(title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    private fun post(id: Int, notification: Notification) {
        // Posting to a channel that does not exist drops the notification
        // silently, and AgentWatch can post before the foreground service ever
        // ran, so the channels are guaranteed here rather than only at service
        // start.
        ensureChannels()
        if (!canNotify()) {
            Log.i(TAG, "notification $id suppressed: notifications disabled")
            return
        }
        try {
            manager.notify(id, notification)
            Log.i(TAG, "posted notification $id")
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
            Log.w(TAG, "notification $id refused", e)
        }
    }

    private fun openApp(sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (sessionId != null) putExtra(MainActivity.EXTRA_OPEN_SESSION, sessionId)
        }
        return PendingIntent.getActivity(context, sessionId?.hashCode() ?: 0, intent, pendingFlags())
    }

    private fun pendingFlags() =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        private const val TAG = "NotificationHelper"

        const val CHANNEL_WATCH = "dsh_watch"
        const val CHANNEL_ALERTS = "dsh_alerts"
        const val ID_WATCH = 1001
        const val ID_NEEDS_YOU = 1002
        const val ID_REVOKED = 1003
        const val ID_TRANSPORT_BASE = 1100
        const val KIND_APPROVAL = "approval"
    }
}
