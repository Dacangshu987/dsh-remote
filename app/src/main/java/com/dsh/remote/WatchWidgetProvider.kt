package com.dsh.remote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date

/**
 * Home-screen status: is the background watcher holding a connection to the
 * host? Purely a read of [HostWatchState], so rendering never starts a service.
 *
 * Tap opens the app; the long-press "重新配对" is left to the launcher, since a
 * widget cannot host a second reliable action across launchers.
 */
class WatchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            manager.updateAppWidget(appWidgetId, buildViews(context))
        }
    }

    companion object {
        /** Ask every placed widget to redraw. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, WatchWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_watch)

            val connected = HostWatchState.isConnected(context)
            views.setTextViewText(
                R.id.widgetStatus,
                context.getString(if (connected) R.string.widget_connected else R.string.widget_offline),
            )

            val updatedAt = HostWatchState.updatedAt(context)
            views.setTextViewText(
                R.id.widgetUpdated,
                if (updatedAt > 0L) {
                    val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(updatedAt))
                    context.getString(R.string.widget_updated, stamp)
                } else {
                    context.getString(R.string.widget_never)
                },
            )

            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return views
        }
    }
}
