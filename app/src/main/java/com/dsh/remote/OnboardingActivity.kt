package com.dsh.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dsh.remote.databinding.ActivityOnboardingBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * First-run pairing screen. Shows exactly two options: scan the pairing QR
 * code, or paste the pairing link. On success the host base URL is persisted
 * and the full pairing URL is handed back so MainActivity can load it in the
 * WebView (the plugin performs the accept handshake in-browser).
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAIR_URL = "dsh_pair_url"
    }

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnPaste.setOnClickListener { promptForLink() }
    }

    private fun startScan() {
        IntentIntegrator(this)
            .setCaptureActivity(CaptureActivity::class.java)
            .setOrientationLocked(false)
            .setPrompt(getString(R.string.onboarding_scan_prompt))
            .setBeepEnabled(false)
            .initiateScan()
    }

    private fun promptForLink() {
        val input = EditText(this).apply {
            hint = getString(R.string.onboarding_link_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
        }
        val margin = (16 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_link_title)
            .setMessage(R.string.onboarding_link_message)
            .setView(input, margin, (8 * resources.displayMetrics.density).toInt(), margin, 0)
            .setPositiveButton(R.string.onboarding_pair) { _, _ ->
                handleLink(input.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (resultCode == Activity.RESULT_OK && result.contents != null) {
                handleLink(result.contents)
            } else {
                toast(R.string.onboarding_scan_cancelled)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleLink(raw: String) {
        val target = PairingController.parsePairLink(raw)
        if (target == null) {
            toast(R.string.onboarding_link_invalid)
            return
        }
        // Persist the host base URL; MainActivity loads the pairing URL.
        val existing = ConfigStore.load(this)
        ConfigStore.save(this, existing.copy(host = target.origin))
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PAIR_URL, target.pairUrl))
        finish()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }
}
