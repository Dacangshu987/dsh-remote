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
 * code, or paste the pairing link. On a successful accept the pairing cookie
 * is stored and the activity returns OK so MainActivity can load the remote
 * page directly.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnPaste.setOnClickListener { promptForLink() }
    }

    private fun startScan() {
        if (busy) return
        IntentIntegrator(this)
            .setCaptureActivity(CaptureActivity::class.java)
            .setOrientationLocked(false)
            .setPrompt(getString(R.string.onboarding_scan_prompt))
            .setBeepEnabled(false)
            .initiateScan()
    }

    private fun promptForLink() {
        if (busy) return
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
        if (busy) return
        val target = PairingController.parsePairLink(raw)
        if (target == null) {
            toast(R.string.onboarding_link_invalid)
            return
        }
        busy = true
        binding.btnScan.isEnabled = false
        binding.btnPaste.isEnabled = false

        // Do the accept off the UI thread.
        Thread {
            val outcome = PairingController.accept(target.origin, target.token)
            runOnUiThread {
                busy = false
                binding.btnScan.isEnabled = true
                binding.btnPaste.isEnabled = true
                if (outcome == "ok") {
                    // Save the host so MainActivity loads the remote page directly.
                    val existing = ConfigStore.load(this)
                    ConfigStore.save(this, existing.copy(host = target.origin))
                    toast(R.string.onboarding_success)
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    toast(outcome)
                }
            }
        }.start()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
