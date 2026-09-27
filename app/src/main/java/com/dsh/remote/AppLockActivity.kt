package com.dsh.remote

import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The lock screen shown instead of the WebView while [AppLockState] says the app
 * is locked.
 *
 * Verification is chosen at runtime by [LockCapability] rather than assumed:
 *  1. `BiometricPrompt` with whichever authenticator mask the device accepts.
 *  2. The platform keyguard confirmation (`createConfirmDeviceCredentialIntent`)
 *     when no mask works but a secure lock screen exists — the path OEMs
 *     implement most consistently, and the reason a vivo device that *does* have
 *     a lock screen no longer gets told to go and set one up.
 *  3. Only when neither is available is the user asked to configure a lock
 *     screen.
 *
 * Every path is guarded: a lock screen must never crash the app, and it must
 * never fake a successful check.
 */
class AppLockActivity : AppCompatActivity() {

    private var cancellationSignal: CancellationSignal? = null

    private val confirmDeviceCredential = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) unlock() else leaveLocked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_applock)
        // A recreation (rotation, or returning from a prompt) must not stack a
        // second prompt on top of the first.
        if (savedInstanceState != null) return
        try {
            startVerification()
        } catch (e: Exception) {
            // Never crash on the lock screen: report and stay closed.
            Log.e(TAG, "cannot start verification", e)
            showUnavailable(R.string.lock_failed)
        }
    }

    private fun startVerification() {
        when (val capability = LockCapability.detect(this)) {
            is LockCapability.Biometric -> promptBiometric(capability)
            LockCapability.KeyguardFallback -> promptKeyguard()
            LockCapability.None -> showUnavailable(R.string.lock_no_credential)
        }
    }

    private fun promptBiometric(capability: LockCapability.Biometric) {
        val builder = BiometricPrompt.Builder(this).setTitle(getString(R.string.lock_title))
        // Allowing DEVICE_CREDENTIAL switches the prompt to its full-screen
        // keyguard form, which rejects setSubtitle; only the biometric-only form
        // may carry one.
        if (!capability.canUseDeviceCredential) {
            builder.setSubtitle(getString(R.string.lock_subtitle))
        }
        builder.setAllowedAuthenticators(capability.authenticators)

        val prompt = try {
            builder.build()
        } catch (e: Exception) {
            // Some OEM builds reject a mask only at build time.
            Log.w(TAG, "BiometricPrompt build failed; falling back to keyguard", e)
            promptKeyguard()
            return
        }

        cancellationSignal = CancellationSignal()
        try {
            prompt.authenticate(
                cancellationSignal!!,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        unlock()
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence?) {
                        Log.i(TAG, "biometric error $code: $message")
                        leaveLocked()
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "authenticate() failed; falling back to keyguard", e)
            promptKeyguard()
        }
    }

    /** Platform PIN/pattern/password confirmation. */
    private fun promptKeyguard() {
        val intent = LockCapability.keyguardConfirmIntent(this)
        if (intent == null) {
            showUnavailable(R.string.lock_no_credential)
            return
        }
        try {
            confirmDeviceCredential.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "cannot launch device credential confirmation", e)
            showUnavailable(R.string.lock_failed)
        }
    }

    private fun unlock() {
        AppLockState.markUnlocked()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showUnavailable(messageRes: Int) {
        // Append what the device actually reported: without it, a user on an OEM
        // build that misreports its own capability has no way to tell the app
        // what is wrong, and every report costs a round trip.
        val detail = LockCapability.describe(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_title)
            .setMessage(getString(messageRes) + "\n\n" + detail)
            .setCancelable(false)
            .setPositiveButton(R.string.lock_open_settings) { _, _ -> openSecuritySettings() }
            .setNegativeButton(R.string.lock_leave) { _, _ -> leaveLocked() }
            .show()
    }

    /**
     * Send the user to the lock-screen settings **without** tearing down the
     * task.
     *
     * An earlier version also called `finishAndRemoveTask()` here. That removed
     * the app's only task while Settings was on top, so coming back landed on
     * the launcher — indistinguishable from a crash, and it discarded the
     * session the user came from. Keeping the task alive means Back returns to
     * the lock screen, which re-checks and unlocks once a credential exists.
     */
    private fun openSecuritySettings() {
        // Prefer the lock-screen page; fall back to the settings root, which
        // every device resolves.
        val security = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        val target = if (security.resolveActivity(packageManager) != null) {
            security
        } else {
            Intent(android.provider.Settings.ACTION_SETTINGS)
        }
        try {
            startActivity(target)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${target.action}", e)
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Close the whole task without touching the watcher service. */
    private fun leaveLocked() {
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        cancellationSignal?.cancel()
        cancellationSignal = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AppLockActivity"
    }
}
