package com.dsh.remote

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Shared "update available" flow: install-permission gate, release-page
 * fallback, APK download with a progress dialog, and hand-off to the system
 * installer. Lives in one place because both the launch check and the manual
 * "check for updates" UI need exactly this sequence.
 *
 * One instance per UI owner; call [destroy] from `Activity.onDestroy()` so the
 * progress dialog does not outlive the activity.
 */
class UpdateInstaller(private val activity: Activity) {

    private var progressDialog: ProgressDialog? = null

    /** Ask the user, then run the whole update flow on confirmation. */
    fun prompt(info: UpdateChecker.UpdateInfo) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_message, BuildConfig.VERSION_NAME, info.latestVersion))
            .setPositiveButton(R.string.update_go) { _, _ -> start(info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun start(info: UpdateChecker.UpdateInfo) {
        // Android 8+ requires the "install unknown apps" capability per app.
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_install_perm_title)
                .setMessage(R.string.update_install_perm_message)
                .setPositiveButton(R.string.update_go_settings) { _, _ -> openInstallPermissionSettings() }
                .setNegativeButton(R.string.update_later, null)
                .show()
            return
        }

        // No APK attached to the release: fall back to the release page.
        if (info.apkUrl.isEmpty()) {
            val page = info.releaseUrl.ifEmpty { "https://github.com/${UpdateChecker.REPO}/releases" }
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
            } catch (_: Exception) {
                toast(R.string.update_open_failed)
            }
            return
        }

        downloadAndInstall(info.apkUrl)
    }

    private fun openInstallPermissionSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: Exception) {
            toast(R.string.update_open_failed)
        }
    }

    /** Download with a horizontal progress dialog, then install. */
    private fun downloadAndInstall(apkUrl: String) {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        val dest = File(dir, "dsh-remote-update.apk")

        val dialog = ProgressDialog(activity)
        dialog.setTitle(R.string.update_downloading_title)
        dialog.setMessage(activity.getString(R.string.update_downloading_message))
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.setCancelable(false)
        dialog.show()
        progressDialog = dialog

        Thread {
            val ok = UpdateChecker.download(apkUrl, dest) { p ->
                activity.runOnUiThread { if (!activity.isFinishing) dialog.progress = p }
            }
            activity.runOnUiThread {
                dismissProgress()
                if (ok) installApk(dest) else toast(R.string.update_download_failed)
            }
        }.start()
    }

    /** Hand the downloaded APK to the system installer via FileProvider. */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            toast(R.string.update_install_failed)
        }
    }

    /** Drop the progress dialog; safe to call more than once. */
    fun destroy() {
        dismissProgress()
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_LONG).show()
    }
}
