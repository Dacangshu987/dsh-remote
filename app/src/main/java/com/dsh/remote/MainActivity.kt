package com.dsh.remote

import android.Manifest
import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dsh.remote.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Main remote screen: a full-screen WebView loading the host's `/m/` page.
 *
 * First run (no host) and any detected pairing loss go through
 * [OnboardingActivity]. Load failures (host unreachable, HTTP errors) show a
 * friendly overlay with a "retry" and a "re-pair" action. A centered spinner
 * covers the pairing-verification / first-load window. New releases are
 * detected from GitHub and downloaded + installed in-app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private var eventMonitor: EventMonitor? = null
    private var pendingSessionId: String? = null

    companion object {
        private const val REQ_ONBOARD = 1001
        const val EXTRA_SESSION_ID = "dsh_session_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = ConfigStore.load(this)
        setupWebView()
        setupErrorActions()
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }
        checkForUpdates()
        verifyPairingThenLoad()
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
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
                applyPendingSessionId()
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
                if (!request.isForMainFrame) return
                hideLoading()
                if (errorResponse.statusCode == 403) {
                    // Pairing was revoked/lost on the host -> go re-pair (keep host/cookie).
                    startOnboarding()
                } else {
                    showErrorPage()
                }
            }
        }
        binding.webView.webChromeClient = WebChromeClient()
        CookieManager.getInstance().setAcceptCookie(true)
    }

    private fun setupErrorActions() {
        binding.btnRetry.setOnClickListener {
            hideErrorPage()
            verifyPairingThenLoad()
        }
        binding.btnRepair.setOnClickListener {
            // Re-pair on the onboarding screen; keep the saved host and cookie.
            startOnboarding()
        }
    }

    /** Verify pairing before loading; send pairing loss back to onboarding. */
    private fun verifyPairingThenLoad() {
        val host = ConfigStore.normalizeHost(config.host)
        if (host == null) {
            startOnboarding()
            return
        }
        showLoading()
        Thread {
            val status = PairingController.pairingStatus(host)
            runOnUiThread {
                when (status) {
                    PairingController.PairingStatus.PAIRED -> {
                        hideErrorPage()
                        // Keep the spinner up; onPageStarted/onPageFinished own it.
                        binding.webView.loadUrl(host + "/m/")
                        // Start background event monitoring (SSE + heartbeat).
                        requestNotificationPermission()
                        eventMonitor = EventMonitor(this@MainActivity, host).also { it.start() }
                    }
                    PairingController.PairingStatus.UNPAIRED -> {
                        hideLoading()
                        eventMonitor?.stop(); eventMonitor = null
                        startOnboarding()
                    }
                    PairingController.PairingStatus.UNAVAILABLE -> {
                        hideLoading()
                        eventMonitor?.stop(); eventMonitor = null
                        showErrorPage()
                    }
                }
            }
        }.start()
    }

    /** Background check for a newer release; prompts if one exists. */
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

    /** Download the release APK and install it (or fall back to the release page). */
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

    private fun startOnboarding() {
        startActivityForResult(Intent(this, OnboardingActivity::class.java), REQ_ONBOARD)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_ONBOARD) {
            if (resultCode == Activity.RESULT_OK) {
                config = ConfigStore.load(this)
                verifyPairingThenLoad()
            } else {
                // User backed out without (re)pairing.
                showErrorPage()
                Toast.makeText(this, R.string.not_paired_message, Toast.LENGTH_LONG).show()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

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

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        eventMonitor?.isForeground = true
    }

    override fun onPause() {
        super.onPause()
        eventMonitor?.isForeground = false
    }

    override fun onDestroy() {
        eventMonitor?.stop()
        eventMonitor = null
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    /** Extract sessionId from a notification-triggered Intent. */
    private fun handleNotificationIntent(intent: Intent?) {
        val sid = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sid.isNullOrEmpty()) return
        pendingSessionId = sid
        applyPendingSessionId()
    }

    /** If the WebView is loaded and ready, navigate to the session. */
    private fun applyPendingSessionId() {
        val sid = pendingSessionId ?: return
        if (binding.webView.url?.contains("/m/") != true) return
        pendingSessionId = null
        val json = "window.__dsh_navigateToSession = { sessionId: \"${escapeJs(sid)}\" }"
        binding.webView.evaluateJavascript(json, null)
    }

    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("\n", "\\n")
    }
}
