package com.dsh.remote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * Receives `ACTION_SEND` from other apps.
 *
 * Text goes to the clipboard, because the composer belongs to the official Web
 * GUI and typing into it is not part of any published contract — the system
 * paste affordance is honest about what actually happened. Images and files are
 * copied into the app's own storage first, so the reference survives the source
 * app losing its URI grant.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_SEND) {
            finish()
            return
        }

        when {
            intent.type?.startsWith("text/") == true -> shareText(intent.getStringExtra(Intent.EXTRA_TEXT))
            else -> shareStream()
        }

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun shareText(text: String?) {
        if (text.isNullOrBlank()) {
            toast(R.string.share_nothing)
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("DSH 分享", text))
        toast(R.string.share_text_copied)
    }

    /** Copy the shared stream into our own files so the URI stays readable. */
    private fun shareStream() {
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            toast(R.string.share_nothing)
            return
        }
        val name = intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "shared"
        try {
            val dir = File(filesDir, "shared").apply { mkdirs() }
            val dest = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                toast(R.string.share_failed)
                return
            }
            toast(getString(R.string.share_file_saved, dest.name))
        } catch (_: Exception) {
            toast(R.string.share_failed)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
