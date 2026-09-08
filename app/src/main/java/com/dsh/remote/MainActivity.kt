package com.dsh.remote

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.dsh.remote.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Thin full-screen WebView shell for the DSH remote-web-ui plugin (0.3.x).
 * The host serves the same official Web GUI to phones and PCs; the plugin
 * injects a portrait/touch adaptation layer and closes the pairing loop
 * in-browser (`/pair-accept` -> `/pair-app` -> `/`). This activity only
 * persists the host base URL, loads it, and surfaces a friendly overlay when
 * the host is unreachable, plus the APK self-update path.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig

    companion object {
        private const val REQ_ONBOARD = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = ConfigStore.load(this)
        setupWebView()
        setupActions()
        checkForUpdates()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleSystemBack()
            }
        })

        val host = ConfigStore.normalizeHost(config.host)
        if (host == null) {
            startOnboarding()
        } else {
            loadHome(host)
        }
    }

    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false // stay in the WebView

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                showLoading()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                hideLoading()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // React only to the main page, not to a failing sub-resource.
                if (request.isForMainFrame) {
                    hideLoading()
                    showErrorPage()
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    hideLoading()
                    showErrorPage()
                }
            }
        }
        binding.webView.webChromeClient = WebChromeClient()
    }

    private fun setupActions() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }
        binding.btnRetry.setOnClickListener {
            hideErrorPage()
            val host = ConfigStore.normalizeHost(config.host)
            if (host == null) startOnboarding() else loadHome(host)
        }
        binding.btnRepair.setOnClickListener {
            startOnboarding()
        }
    }

    private fun loadHome(host: String) {
        hideErrorPage()
        binding.webView.loadUrl("$host/")
    }

    private fun startOnboarding() {
        startActivityForResult(Intent(this, OnboardingActivity::class.java), REQ_ONBOARD)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_ONBOARD) {
            if (resultCode == Activity.RESULT_OK) {
                config = ConfigStore.load(this)
                val pairUrl = data?.getStringExtra(OnboardingActivity.EXTRA_PAIR_URL)
                if (pairUrl != null) {
                    hideErrorPage()
                    binding.webView.loadUrl(pairUrl)
                } else {
                    ConfigStore.normalizeHost(config.host)?.let { loadHome(it) }
                }
            } else {
                // User backed out without pairing.
                val host = ConfigStore.normalizeHost(config.host)
                if (host == null) {
                    showErrorPage()
                    Toast.makeText(this, R.string.not_paired_message, Toast.LENGTH_LONG).show()
                } else {
                    loadHome(host)
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun handleSystemBack() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    /* ── Update checker ─────────────────────────────────────────────── */

    private fun checkForUpdates() {
        UpdateChecker.check(BuildConfig.VERSION_NAME) { info ->
            runOnUiThread {
                if (info.isNewer) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.update_title)
                        .setMessage(getString(R.string.update_message, BuildConfig.VERSION_NAME, info.latestVersion))
                        .setPositiveButton(R.string.update_go) { _, _ -> goUpdate(info) }
                        .setNegativeButton(R.string.update_later, null)
                        .show()
                }
            }
        }
    }

    private fun goUpdate(info: UpdateChecker.UpdateInfo) {
        // Android 8+ requires the "install unknown apps" capability per app.
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
            // No APK attached to the release: open the release page instead.
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

    /** Download with a horizontal progress dialog, then install. */
    private fun downloadAndInstall(apkUrl: String) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val dest = File(dir, "dsh-remote-update.apk")
        val dialog = ProgressDialog(this)
        dialog.setTitle(R.string.update_downloading_title)
        dialog.setMessage(getString(R.string.update_downloading_message))
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.setCancelable(false)
        dialog.show()
        Thread {
            val ok = UpdateChecker.download(apkUrl, dest) { p ->
                runOnUiThread { dialog.progress = p }
            }
            runOnUiThread {
                dialog.dismiss()
                if (ok) installApk(dest)
                else Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Hand the downloaded APK to the system installer via FileProvider. */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
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

    /* ── Overlay helpers ────────────────────────────────────────────── */

    private fun showErrorPage() {
        binding.errorView.visibility = View.VISIBLE
    }

    private fun hideErrorPage() {
        binding.errorView.visibility = View.GONE
    }

    private fun showLoading() {
        binding.loading.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loading.visibility = View.GONE
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
