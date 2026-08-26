package com.dsh.remote

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update checker against the project's GitHub releases
 * (https://github.com/Dacangshu987/dsh-remote). Queries the latest release,
 * compares its tag against the installed version, and locates the release's
 * APK asset so the app can download and install it in place.
 */
object UpdateChecker {

    const val REPO = "Dacangshu987/dsh-remote"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** Result of an update check. */
    data class UpdateInfo(
        val latestVersion: String,
        val releaseUrl: String,
        val apkUrl: String,
        val isNewer: Boolean,
    )

    /** Run the check off the UI thread. isNewer=false when no update / no network. */
    fun check(currentVersion: String, onResult: (UpdateInfo) -> Unit) {
        Thread { onResult(fetch(currentVersion)) }.start()
    }

    private fun fetch(currentVersion: String): UpdateInfo {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(RELEASES_API)
            if (conn.responseCode == 200) {
                val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = obj.optString("tag_name", "").removePrefix("v")
                val releaseUrl = obj.optString("html_url", "")
                val apkUrl = findApkAsset(obj)
                val isNewer = tag.isNotEmpty() && compareVersions(tag, currentVersion) > 0
                UpdateInfo(tag, releaseUrl, apkUrl, isNewer)
            } else {
                UpdateInfo("", "", "", false)
            }
        } catch (_: Exception) {
            UpdateInfo("", "", "", false)
        } finally {
            conn?.disconnect()
        }
    }

    /** First release asset whose name ends with `.apk` (or "" if none). */
    private fun findApkAsset(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return a.optString("browser_download_url", "")
            }
        }
        return ""
    }

    /**
     * Download a file with an on-thread progress callback (0..100).
     * Returns true on success. Runs on the calling thread.
     */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit = {}): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url)
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) onProgress(((written * 100) / total).toInt())
                    }
                }
            }
            true
        } catch (_: Exception) {
            dest.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        // GitHub API and asset redirects require a non-empty User-Agent.
        c.setRequestProperty("User-Agent", "DSH-Remote-Android")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        return c
    }

    /** Compare dotted numeric versions; returns >0 if a > b. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
