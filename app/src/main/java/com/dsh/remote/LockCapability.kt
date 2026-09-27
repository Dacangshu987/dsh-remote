package com.dsh.remote

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.util.Log

/**
 * What this device can actually use to verify the user, probed in a fixed order
 * instead of trusting a single API.
 *
 * `BiometricManager.canAuthenticate()` is the obvious check and the only one
 * that works for biometrics, but it is not dependable across OEMs: several
 * builds (observed on vivo) report "no credential" for a device that does have
 * a lock screen, and the combined `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` mask is
 * not supported everywhere it is documented to be. The fallback order below
 * exists so a device with a usable lock screen is never told to go and set one
 * up.
 */
sealed interface LockCapability {

    /** BiometricPrompt with this authenticator mask can be used. */
    data class Biometric(val authenticators: Int, val canUseDeviceCredential: Boolean) : LockCapability

    /**
     * No usable BiometricPrompt configuration, but the device does have a
     * secure lock screen: fall back to the platform keyguard confirmation,
     * which OEMs implement consistently.
     */
    data object KeyguardFallback : LockCapability

    /** Nothing to verify against; the user must configure a lock screen. */
    data object None : LockCapability

    companion object {
        private const val TAG = "LockCapability"

        fun detect(context: Context): LockCapability {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                detectModern(context)?.let { return it }
            }
            // API 26..29 (and any modern device that refused every mask):
            // a secure keyguard is enough to run the platform confirmation.
            return if (hasSecureKeyguard(context)) {
                LockCapability.KeyguardFallback
            } else {
                LockCapability.None
            }
        }

        /** @returns a usable configuration, or null when none of the masks work. */
        private fun detectModern(context: Context): LockCapability? {
            val manager = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            if (manager == null) {
                Log.w(TAG, "no BiometricManager on this device")
                return null
            }

            val weak = BiometricManager.Authenticators.BIOMETRIC_WEAK
            val credential = BiometricManager.Authenticators.DEVICE_CREDENTIAL

            // 1) Strongest: biometric or the lock-screen PIN/pattern.
            if (canAuthenticate(manager, weak or credential)) {
                return LockCapability.Biometric(weak or credential, canUseDeviceCredential = true)
            }
            // 2) Biometrics only — common where DEVICE_CREDENTIAL is unsupported.
            if (canAuthenticate(manager, weak)) {
                return LockCapability.Biometric(weak, canUseDeviceCredential = false)
            }
            // 3) Lock-screen credential only.
            if (canAuthenticate(manager, credential)) {
                return LockCapability.Biometric(credential, canUseDeviceCredential = true)
            }
            return null
        }

        private fun canAuthenticate(manager: BiometricManager, mask: Int): Boolean = try {
            manager.canAuthenticate(mask) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            // Some builds throw for masks they do not know (API 30 constant on a
            // back-ported framework), which must not be fatal.
            Log.w(TAG, "canAuthenticate(0x${mask.toString(16)}) threw", e)
            false
        }

        /** Whether a non-swipe lock screen is configured. */
        fun hasSecureKeyguard(context: Context): Boolean {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
            return try {
                keyguard.isKeyguardSecure
            } catch (e: Exception) {
                Log.w(TAG, "isKeyguardSecure threw", e)
                false
            }
        }

        /** The platform PIN/pattern/password confirmation, or null if unusable. */
        @Suppress("DEPRECATION")
        fun keyguardConfirmIntent(context: Context): Intent? {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return null
            return try {
                keyguard.createConfirmDeviceCredentialIntent(
                    context.getString(R.string.lock_title),
                    context.getString(R.string.lock_subtitle),
                )
            } catch (e: Exception) {
                Log.w(TAG, "createConfirmDeviceCredentialIntent threw", e)
                null
            }
        }

        /**
         * One-line summary of what this device reported, for the "cannot verify"
         * screen.
         *
         * Multi-vendor adaptation is guesswork without this: the same Android
         * release reports different `canAuthenticate` results per OEM, so the
         * device is asked to state its own answers rather than the app assuming.
         */
        fun describe(context: Context): String {
            val sdk = Build.VERSION.SDK_INT
            val keyguard = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val secure = hasSecureKeyguard(context)
            if (sdk < Build.VERSION_CODES.R) {
                return "$keyguard · API $sdk · 锁屏=$secure · 无 BiometricPrompt 组合（<30）"
            }
            val manager = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
                ?: return "$keyguard · API $sdk · 锁屏=$secure · 无 BiometricManager"
            val weak = BiometricManager.Authenticators.BIOMETRIC_WEAK
            val cred = BiometricManager.Authenticators.DEVICE_CREDENTIAL
            fun state(mask: Int, name: String): String {
                val code = try {
                    manager.canAuthenticate(mask)
                } catch (e: Exception) {
                    -1
                }
                val label = when (code) {
                    BiometricManager.BIOMETRIC_SUCCESS -> "OK"
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "未录入"
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "无硬件"
                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "硬件不可用"
                    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "需安全更新"
                    -1 -> "抛异常"
                    else -> "code=$code"
                }
                return "$name=$label"
            }
            return "$keyguard · API $sdk · 锁屏=$secure · " +
                state(weak, "生物") + " · " +
                state(cred, "锁屏凭据") + " · " +
                state(weak or cred, "组合")
        }
    }
}
