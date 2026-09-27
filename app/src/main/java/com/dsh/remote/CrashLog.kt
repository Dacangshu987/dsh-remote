package com.dsh.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Records uncaught exceptions to a file and surfaces them on screen.
 *
 * This exists because the lock screen and the foreground service both run in
 * situations where nobody can attach a debugger, and "it still crashes" is not
 * actionable without a stack trace. The handler deliberately does the minimum
 * work possible — a crash handler that itself crashes is worse than none — and
 * never swallows the failure: the original uncaught handler still runs.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_STACK_CHARS = 8_000

    /** Install the handler. Called once from the Application. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = render(thread, error)
                File(app.filesDir, FILE_NAME).writeText(text)
                Log.e(TAG, "uncaught exception on ${thread.name}", error)
                // Show it: the user can read and copy it without adb.
                app.startActivity(
                    Intent(app, CrashReportActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (_: Throwable) {
                // Never let reporting replace the original failure.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** The most recent crash text, or null when none was recorded. */
    fun last(context: Context): String? =
        File(context.filesDir, FILE_NAME)
            .takeIf { it.isFile }
            ?.readText()
            ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun render(thread: Thread, error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val stack = writer.toString().take(MAX_STACK_CHARS)
        val stamp = DateFormat.getDateTimeInstance().format(Date())
        return buildString {
            appendLine("时间：$stamp")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("线程：${thread.name}")
            appendLine("异常：${error.javaClass.name}")
            appendLine("信息：${error.message}")
            appendLine()
            append(stack)
        }
    }
}
