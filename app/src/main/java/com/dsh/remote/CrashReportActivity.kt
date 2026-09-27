package com.dsh.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dsh.remote.databinding.ActivityCrashReportBinding

/**
 * Shows the last recorded crash so it can be read (or copied) without a
 * computer. Reached only from [CrashLog]'s handler.
 */
class CrashReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val report = CrashLog.last(this)
        binding.crashText.text = report ?: getString(R.string.crash_none)
        binding.btnCopy.setOnClickListener { copy(report.orEmpty()) }
        binding.btnClose.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener {
            CrashLog.clear(this)
            Toast.makeText(this, R.string.crash_cleared, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun copy(report: String) {
        if (report.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH crash", report))
        Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
    }
}
