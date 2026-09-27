package com.dsh.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart the background watcher after a reboot.
 *
 * Without this, a restart leaves the app thinking it is watched while nothing
 * is running: the phone silently stops receiving alerts and stops reporting
 * itself online to the host, and the user has no reason to open the app and
 * notice.
 *
 * Only starts the service when the user has it enabled and a host is paired;
 * a foreground service started with nothing to do would put a permanent
 * notification on screen for no reason.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext
        val watchEnabled = app.getSharedPreferences(MainActivity.WATCH_PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_WATCH_ENABLED, true)
        if (!watchEnabled) return
        if (ConfigStore.normalizeHost(ConfigStore.load(app).host) == null) return
        if (SecretStore.credential(app) == null) return

        HostWatchService.start(app)
    }
}
