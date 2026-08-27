package com.dsh.remote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dsh.remote.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Settings screen: manual update check + current version. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.versionText.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)
        binding.updateStatus.text = getString(R.string.settings_update_status_idle)

        binding.rowUpdate.setOnClickListener { checkForUpdates() }

        loadNotificationPrefs()
    }

    private fun loadNotificationPrefs() {
        val s = NotificationPrefs.load(this)
        binding.switchTurnEnd.isChecked = s.turnEnd
        binding.switchApproval.isChecked = s.approval
        binding.switchStall.isChecked = s.stall
        binding.switchTurnStart.isChecked = s.turnStart
        binding.switchOffline.isChecked = s.offline

        binding.switchTurnEnd.setOnCheckedChangeListener { _, v -> saveBit { it.copy(turnEnd = v) } }
        binding.switchApproval.setOnCheckedChangeListener { _, v -> saveBit { it.copy(approval = v) } }
        binding.switchStall.setOnCheckedChangeListener { _, v -> saveBit { it.copy(stall = v) } }
        binding.switchTurnStart.setOnCheckedChangeListener { _, v -> saveBit { it.copy(turnStart = v) } }
        binding.switchOffline.setOnCheckedChangeListener { _, v -> saveBit { it.copy(offline = v) } }
    }

    private fun saveBit(transform: (NotificationPrefs.Settings) -> NotificationPrefs.Settings) {
        val current = NotificationPrefs.load(this)
        NotificationPrefs.save(this, transform(current))
    }

    private fun checkForUpdates() {
        if (busy) return
        busy = true
        binding.updateStatus.text = getString(R.string.settings_update_checking)

        UpdateChecker.check(BuildConfig.VERSION_NAME) { info ->
            runOnUiThread {
                busy = false
                when {
                    info.isNewer -> {
                        binding.updateStatus.text = info.latestVersion
                        showUpdateDialog(info)
                    }
                    else -> {
                        binding.updateStatus.text = getString(R.string.settings_update_up_to_date)
                        Toast.makeText(this, R.string.settings_update_up_to_date, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, BuildConfig.VERSION_NAME, info.latestVersion))
            .setPositiveButton(R.string.update_go) { _, _ -> goUpdate(info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun goUpdate(info: UpdateChecker.UpdateInfo) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_install_perm_title)
                .setMessage(R.string.update_install_perm_message)
                .setPositiveButton(R.string.update_go_settings) { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                        )
                    } catch (_: Exception) {
                        Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
            return
        }
        if (info.apkUrl.isEmpty()) {
            try {
                val page = info.releaseUrl.ifEmpty { "https://github.com/${UpdateChecker.REPO}/releases" }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        downloadAndInstall(info.apkUrl)
    }

    private fun downloadAndInstall(apkUrl: String) {
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val dest = File(dir, "dsh-remote-update.apk")
        val dialog = android.app.ProgressDialog(this)
        dialog.setTitle(R.string.update_downloading_title)
        dialog.setMessage(getString(R.string.update_downloading_message))
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
        dialog.setCancelable(false)
        dialog.show()
        Thread {
            val ok = UpdateChecker.download(apkUrl, dest) { p -> runOnUiThread { dialog.progress = p } }
            runOnUiThread {
                dialog.dismiss()
                if (ok) installApk(dest)
                else Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun installApk(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }
}
