package com.dsh.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import com.dsh.remote.databinding.ActivityMainBinding

/**
 * Main remote screen: a full-screen WebView loading the host's `/m/` page.
 *
 * First run (no host) and any detected pairing loss go through
 * [OnboardingActivity]. Load failures (host unreachable, HTTP errors) show a
 * friendly overlay with a "retry" and a "re-pair" action instead of the raw
 * WebView error page.
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
        setupErrorActions()
        verifyPairingThenLoad()
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

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // React only to the main page, not to a failing sub-resource.
                if (request.isForMainFrame) showErrorPage()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
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
        Thread {
            val status = PairingController.pairingStatus(host)
            runOnUiThread {
                when (status) {
                    PairingController.PairingStatus.PAIRED -> {
                        hideErrorPage()
                        binding.webView.loadUrl(host + "/m/")
                    }
                    PairingController.PairingStatus.UNPAIRED ->
                        startOnboarding()
                    PairingController.PairingStatus.UNAVAILABLE ->
                        showErrorPage()
                }
            }
        }.start()
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

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
