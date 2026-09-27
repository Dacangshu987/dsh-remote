package com.dsh.remote

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dsh.remote.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Thin full-screen WebView shell for the DSH remote-web-ui plugin (0.4.x).
 * The host serves the same official Web GUI to phones and PCs; the plugin
 * injects a portrait/touch adaptation layer and closes the pairing loop
 * in-browser (`/pair-accept` -> `/pair-app` -> `/`). This activity only
 * persists the host base URL, loads it, and surfaces a friendly overlay when
 * the host is unreachable, plus the APK self-update path.
 *
 * It also captures the device credential the plugin injects into the page
 * (`localStorage['dsh-remote-device']`) and hands it to [SecretStore], which is
 * what lets [HostWatchService] notify about agent events while the app is
 * closed.
 *
 * Pairing state is never gated here: the plugin's own 403 / re-scan page stays
 * the authority. The status snapshot only enriches the error page's wording.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private lateinit var updates: UpdateInstaller

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasOnline = false

    /** Set by a notification tap; consumed once the WebView has a page. */
    private var pendingSessionId: String? = null

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onCredentialAvailable()
        } else {
            Toast.makeText(this, R.string.notify_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_ONBOARD = 1001

        /** Extra carrying the session that produced a notification tap. */
        const val EXTRA_OPEN_SESSION = "dsh_open_session"

        /** The plugin's `APP_DEVICE_STORAGE_KEY` (secondary source). */
        private const val DEVICE_STORAGE_KEY = "dsh-remote-device"

        /** The plugin's `config.cookieName` (primary source; user-configurable). */
        private const val DEVICE_COOKIE = "dsh_pair"
        private const val KEY_CONSENT_SHOWN = "watch_consent_shown"
        private const val KEY_BATTERY_ASKED = "battery_exemption_asked"

        /** Where the floating options ball was last dropped. */
        private const val BALL_PREFS = "dsh_remote_ball"
        private const val BALL_KEY_X = "x"
        private const val BALL_KEY_Y = "y"
        private const val BALL_ALPHA = 0.5f

        /** Background-alert preference; BootReceiver reads these too. */
        const val WATCH_PREFS = "dsh_remote_watch"
        const val KEY_WATCH_ENABLED = "watch_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialised before the lock gate on purpose: `finish()` still runs
        // onDestroy, and a lateinit read there crashed the process with
        // UninitializedPropertyAccessException when the lock engaged.
        updates = UpdateInstaller(this)

        // Local app lock: the device credential is a full-control credential for
        // the host, so gate the WebView before any page (and any stored secret
        // use) happens. Returning here keeps the lock screen as the only surface
        // this launch shows.
        if (AppLockState.shouldLock(this)) {
            startActivity(Intent(this, AppLockActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = ConfigStore.load(this)
        pendingSessionId = intent?.getStringExtra(EXTRA_OPEN_SESSION)
        setupWebView()
        setupActions()
        registerNetworkCallback()
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

    override fun onResume() {
        super.onResume()
        // The app is in front of the user now, so anything the notifications
        // were telling them about is on screen. Leaving them would accumulate
        // stale "回复完成" entries for sessions already read.
        //
        // Routed through the service, which owns the record of what it posted;
        // cancelling by enumeration is not an option (see NotificationHelper).
        HostWatchService.clearAlerts(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A notification tap while the WebView is already live.
        intent.getStringExtra(EXTRA_OPEN_SESSION)?.let { sessionId ->
            pendingSessionId = sessionId
            openPendingSessionIfAny()
        }
    }

    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
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
                // Only a real page load counts as the host answering — the
                // about:blank we load while re-pairing must not refresh the
                // "last reachable" hint.
                if (url == null || !url.startsWith("http")) return
                hideErrorPage()
                ConfigStore.markReachable(this@MainActivity)
                config = ConfigStore.load(this@MainActivity)
                captureDeviceCredential(url)
                openPendingSessionIfAny()
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
        binding.btnRetry.setOnClickListener {
            hideErrorPage()
            val host = ConfigStore.normalizeHost(config.host)
            if (host == null) startOnboarding() else loadHome(host)
        }
        binding.btnRepair.setOnClickListener {
            startOnboarding()
        }
        // Diagnostics are easiest to hand over as text.
        binding.errorView.setOnLongClickListener {
            copyHostToClipboard()
            false // let the platform long-press feedback continue
        }
        // The visible options entry. Long-press Back stays as a secondary path
        // for three-button navigation, but it is unreachable under gesture
        // navigation, so the floating ball is the primary one.
        makeOptionsBallDraggable()
    }

    /* ── The floating options ball ──────────────────────────────────── */

    /**
     * Lets the user drag [binding.btnOptions] anywhere on screen and remembers
     * where they left it.
     *
     * A fixed corner is wrong here because the GUI has its own controls in
     * every corner (sidebar toggle top-left, window buttons top-right, composer
     * bottom), and which one is free depends on the layout. Dragging is the
     * only placement that works for everyone, and mirrors the plugin's own
     * draggable whale button.
     */
    private fun makeOptionsBallDraggable() {
        val ball = binding.btnOptions
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f

        /** Where the ball sat (screen space) when this gesture started. */
        var startScreenX = 0f
        var startScreenY = 0f
        var dragging = false
        var moved = false

        applySavedBallPosition()

        // Touch listener first: Android dispatches to the most recently
        // registered listener, so registering it after the click listener let
        // the click win and every drag opened the menu.
        ball.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startScreenX = view.x
                    startScreenY = view.y
                    dragging = true
                    moved = false
                    // Keep the parent from stealing the gesture mid-drag.
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moved = true
                        view.alpha = 0.85f
                    }
                    if (moved) placeBall(startScreenX + dx, startScreenY + dy)
                    // Consume the gesture once it is a drag, so it never also
                    // reads as a tap (which would open the menu on every move).
                    moved
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.alpha = BALL_ALPHA
                    // A drag only moves the ball; it must not open the menu.
                    // A tap is left unconsumed so the click listener fires.
                    if (moved) saveBallPosition()
                    moved
                }
                else -> false
            }
        }
        // Registered last so the touch listener above sees the gesture first.
        ball.setOnClickListener { showOptions() }
    }

    /**
     * Put the ball at a position in **screen** coordinates, kept on screen.
     *
     * Uses translation rather than `x`/`y`: this view is anchored to the parent
     * with `top|end` constraints, so ConstraintLayout re-places it on every
     * layout pass and silently discards any `x`/`y` written directly. That is
     * what made a second drag start from the constraint position (top-right)
     * instead of where the user had left the ball. Translation is an offset
     * applied after layout, so it survives.
     *
     * @param screenX - desired left edge in screen coordinates.
     * @param screenY - desired top edge in screen coordinates.
     */
    private fun placeBall(screenX: Float, screenY: Float) {
        val ball = binding.btnOptions
        val parent = binding.root
        val margin = (8 * resources.displayMetrics.density).toInt()
        val maxX = (parent.width - ball.width - margin).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - ball.height - margin).coerceAtLeast(0).toFloat()

        val clampedX = screenX.coerceIn(0f, maxX)
        val clampedY = screenY.coerceIn(0f, maxY)
        // The untranslated origin is derived from translation itself, so the
        // ball can never drift when the anchors are re-resolved.
        ball.translationX = clampedX - (ball.x - ball.translationX)
        ball.translationY = clampedY - (ball.y - ball.translationY)
    }

    private fun applySavedBallPosition() {
        val prefs = getSharedPreferences(BALL_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(BALL_KEY_X)) return
        // The anchors resolve during the first layout, so the offset can only be
        // applied once the parent has a size.
        binding.btnOptions.post {
            placeBall(prefs.getInt(BALL_KEY_X, 0).toFloat(), prefs.getInt(BALL_KEY_Y, 0).toFloat())
        }
    }

    private fun saveBallPosition() {
        val ball = binding.btnOptions
        getSharedPreferences(BALL_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(BALL_KEY_X, ball.x.toInt())
            .putInt(BALL_KEY_Y, ball.y.toInt())
            .apply()
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

    /* ── Options: the visible ⋯ button (long-press Back as secondary) ── */

    /**
     * Menu for the app's own controls. Long-press Back also opens this, but
     * only three-button navigation can reach it; the visible button is the
     * reliable entry.
     */
    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            showOptions()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun showOptions() {
        val watchOn = watchEnabled()
        val lockOn = AppLockState.isEnabled(this)
        val labels = arrayOf(
            getString(if (watchOn) R.string.options_watch_on else R.string.options_watch_off),
            getString(if (lockOn) R.string.options_lock_on else R.string.options_lock_off),
            getString(R.string.options_refresh),
            getString(R.string.options_check_update),
            getString(R.string.options_re_pair),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.options_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> toggleWatch(!watchOn)
                    1 -> toggleLock(!lockOn)
                    2 -> binding.webView.reload()
                    3 -> checkForUpdates(manual = true)
                    4 -> beginRePairing()
                }
            }
            .setNegativeButton(R.string.options_close, null)
            .show()
    }

    /**
     * Deliberately forget the host and re-enter pairing.
     *
     * A saved host is what sends the app straight to the GUI on every later
     * launch, so without clearing it the pairing screen is genuinely
     * unreachable — and switching hosts (relay ⇄ tunnel) depends on reaching it.
     */
    private fun beginRePairing() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.re_pair_confirm_title)
            .setMessage(R.string.re_pair_confirm_message)
            .setPositiveButton(R.string.re_pair) { _, _ ->
                ConfigStore.forget(this)
                SecretStore.clear(this)
                HostWatchService.stop(this)
                config = ConfigStore.load(this)
                binding.webView.loadUrl("about:blank")
                startOnboarding()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleWatch(enabled: Boolean) {
        setWatchEnabled(enabled)
        if (enabled) {
            onCredentialAvailable()
        } else {
            HostWatchService.stop(this)
            Toast.makeText(this, R.string.watch_off_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleLock(enabled: Boolean) {
        AppLockState.setEnabled(this, enabled)
        if (enabled) Toast.makeText(this, R.string.options_lock_enabled_toast, Toast.LENGTH_LONG).show()
    }

    /**
     * @param manual - true when triggered from the options menu, which reports
     *   the outcome either way (the silent startup check only speaks up when
     *   there is something to install).
     */
    private fun checkForUpdates(manual: Boolean = false) {
        if (manual) {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }
        UpdateChecker.check(BuildConfig.VERSION_NAME) { info ->
            runOnUiThread {
                when {
                    info.isNewer -> updates.prompt(info)
                    !manual -> Unit
                    info.latestVersion.isEmpty() ->
                        Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    else ->
                        Toast.makeText(
                            this,
                            getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    /* ── Background watcher: credential capture + service lifecycle ──── */

    /**
     * Capture the device credential the plugin's `/pair-accept` minted.
     *
     * The canonical location is the **cookie jar**: that endpoint answers with
     * `Set-Cookie: dsh_pair=<deviceId>`, and `CookieManager` exposes it to native
     * code on the main thread. Verified on a real device — the cookie is present
     * while `localStorage['dsh-remote-device']` stays empty, so a
     * localStorage-only read silently yields no credential and the background
     * watcher never starts. localStorage is kept as a secondary source only.
     *
     * @param pageUrl - the finished page's URL, used as the cookie origin.
     */
    private fun captureDeviceCredential(pageUrl: String?) {
        val host = ConfigStore.normalizeHost(config.host)
        val origin = pageUrl?.takeIf { it.startsWith("http") } ?: host
        if (origin == null) return

        // 1) Cookie jar — the authoritative copy.
        val rawCookies = try {
            CookieManager.getInstance().getCookie(origin).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val fromCookie = rawCookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$DEVICE_COOKIE=") }
            ?.substringAfter('=')

        if (!fromCookie.isNullOrEmpty()) {
            SecretStore.putCredential(this, fromCookie)
            onCredentialAvailable()
            return
        }

        // 2) Fallback: the plugin's capture script may have written localStorage
        //    instead (e.g. a cookieless flow).
        binding.webView.evaluateJavascript("localStorage.getItem('$DEVICE_STORAGE_KEY')") { raw ->
            val value = raw?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" } ?: return@evaluateJavascript
            SecretStore.putCredential(this, value)
            onCredentialAvailable()
        }
    }

    /** We have a credential: ask for notification permission, then start watching. */
    private fun onCredentialAvailable() {
        if (!watchEnabled()) {
            // The user turned background alerts off; nothing to do.
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        HostWatchService.start(this)
        maybeShowWatchConsent()
    }

    private fun watchEnabled(): Boolean =
        getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WATCH_ENABLED, true)

    private fun setWatchEnabled(enabled: Boolean) {
        getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WATCH_ENABLED, enabled)
            .apply()
    }

    /**
     * Explain the unavoidable permanent notification once. Android requires a
     * foreground service for background sockets, so this is an informed-consent
     * prompt rather than an optional nicety.
     *
     * The second half of the prompt is the battery-optimisation exemption,
     * because it is the difference between the watcher surviving and not: OEM
     * builds (vivo, Xiaomi, Huawei, OPPO) freeze or kill background apps
     * aggressively, and a Doze-restricted app loses both the socket and the
     * heartbeat. Declining leaves the app fully usable, just without reliable
     * background alerts.
     */
    private fun maybeShowWatchConsent() {
        val prefs = getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONSENT_SHOWN, false)) {
            // Already explained once; still chase the exemption if it is missing,
            // since a battery-saver reset silently drops it.
            maybeRequestBatteryExemption()
            return
        }
        prefs.edit().putBoolean(KEY_CONSENT_SHOWN, true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.watch_toggle_title)
            .setMessage(R.string.watch_toggle_message)
            .setPositiveButton(R.string.watch_keep) { _, _ -> maybeRequestBatteryExemption() }
            .setNegativeButton(R.string.watch_disable) { _, _ ->
                setWatchEnabled(false)
                HostWatchService.stop(this)
                Toast.makeText(this, R.string.watch_off_toast, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Ask to be excluded from battery optimisation, once per install. */
    private fun maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val power = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_allow) { _, _ ->
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    // Some OEM builds block the direct request; the list works.
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, R.string.battery_open_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * A notification tap carries a session id. Handing it to the official GUI
     * needs a route that is not part of any published contract, so the app only
     * opens the workspace and says so rather than faking a deep link.
     */
    private fun openPendingSessionIfAny() {
        val sessionId = pendingSessionId ?: return
        pendingSessionId = null
        Toast.makeText(this, getString(R.string.notify_open_fallback, sessionId.take(6)), Toast.LENGTH_SHORT).show()
    }

    /* ── Connectivity: reconnect when the phone comes back online ───── */

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        wasOnline = hasInternet(cm)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val firstTimeOnline = !wasOnline
                wasOnline = true
                // Only the offline -> online edge should reload; the callback
                // also fires for every network change while already online.
                if (firstTimeOnline && !isFinishing) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.reconnected, Toast.LENGTH_SHORT).show()
                        binding.webView.reload()
                    }
                }
            }

            override fun onLost(network: Network) {
                wasOnline = hasInternet(cm)
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun hasInternet(cm: ConnectivityManager): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /* ── Overlay helpers ────────────────────────────────────────────── */

    private fun showErrorPage() {
        binding.errorView.visibility = View.VISIBLE
        fillDiagnostics()
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

    /** Render the "what exactly failed" block, then refine it with live status. */
    private fun fillDiagnostics() {
        val host = ConfigStore.normalizeHost(config.host) ?: config.host
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val online = cm?.let { hasInternet(it) } ?: false

        val lines = mutableListOf(
            getString(R.string.diag_host, host),
            getString(if (online) R.string.diag_network_online else R.string.diag_network_offline),
            lastReachableLine(),
        )
        binding.errorStatus.text = TextUtils.join("\n", lines)

        // Ask the host directly — this separates "host down" from "GUI failed"
        // and surfaces a dead tunnel/relay or a revoked pairing.
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { HostStatusClient.fetch(host) }
            if (isFinishing || isDestroyed) return@launch
            val extra = mutableListOf(
                getString(
                    if (status.reachable) R.string.diag_host_reachable else R.string.diag_host_unreachable,
                ),
            )
            if (status.phase == "stopped") extra += getString(R.string.diag_device_revoked)
            if (status.tunnelState == "failed") extra += getString(R.string.diag_tunnel_failed)
            if (status.relayState == "failed") extra += getString(R.string.diag_relay_failed)
            extra += getString(R.string.copy_host_hint)
            lines.addAll(extra)
            binding.errorStatus.text = TextUtils.join("\n", lines)
        }
    }

    private fun lastReachableLine(): String {
        if (config.lastReachableAt <= 0L) return getString(R.string.diag_never)
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(config.lastReachableAt))
        return getString(R.string.diag_last_ok, stamp)
    }

    private fun copyHostToClipboard(): Boolean {
        val host = ConfigStore.normalizeHost(config.host) ?: return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH host", host))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb)
        }
        networkCallback = null
        // Defensive: onDestroy also runs on the locked path, where the WebView
        // was never inflated. A lateinit read here kills the process.
        if (::updates.isInitialized) updates.destroy()
        if (::binding.isInitialized) binding.webView.destroy()
        super.onDestroy()
    }
}
