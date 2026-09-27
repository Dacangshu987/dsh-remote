package com.dsh.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest storage for the one secret this app holds: the DSH device
 * credential (`dsh-remote-device`), which the plugin's channel gate accepts as
 * the `device` query parameter on WebSocket upgrades and as the
 * `x-dsh-remote-device` header on HTTP calls.
 *
 * The key lives in the Android Keystore and never leaves it, so the credential
 * is not readable even from a filesystem dump of the app's data directory —
 * unlike the WebView's own cookie/localStorage storage.
 *
 * No `setUserAuthenticationRequired`, deliberately: the background watcher must
 * be able to decrypt while the app is locked and in the background.
 */
object SecretStore {

    private const val PREFS = "dsh_remote_secrets"
    private const val KEY_CREDENTIAL = "device_credential"
    private const val KEY_ALIAS = "dsh_remote_credential_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /** Persist [credential] encrypted, replacing any previous value. */
    fun putCredential(context: Context, credential: String) {
        if (credential.isEmpty()) return
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val payload = cipher.iv + cipher.doFinal(credential.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_CREDENTIAL, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    /** The stored credential, or null when absent or undecryptable. */
    fun credential(context: Context): String? {
        val stored = prefs(context).getString(KEY_CREDENTIAL, null) ?: return null
        return try {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH),
                )
            }
            cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH)
                .toString(Charsets.UTF_8)
                .ifEmpty { null }
        } catch (_: Exception) {
            // Keystore key invalidated (reinstall, lock-screen change, restore):
            // drop the unusable ciphertext so the next load can re-capture it.
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CREDENTIAL).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The app's AES key, created on first use and otherwise reused. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
