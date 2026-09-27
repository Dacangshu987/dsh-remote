package com.dsh.remote

import org.json.JSONObject
import android.util.Log
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

    private const val TAG = "UpdateChecker"

    const val REPO = "Dacangshu987/dsh-remote"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** API-free fallback: this redirects to `.../releases/tag/<tag>`. */
    private const val LATEST_RELEASE_PAGE = "https://github.com/$REPO/releases/latest"

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

    /**
     * Resolve the latest release.
     *
     * Two transports, because `api.github.com` is blocked on some networks
     * (observed returning 403 for every repository) while `github.com` itself
     * works. The API is tried first for its exact asset metadata; the plain
     * `releases/latest` redirect is the fallback and needs no API at all.
     */
    private fun fetch(currentVersion: String): UpdateInfo {
        apiRelease()?.let {
            Log.i(TAG, "release via api: ${it.latestVersion}")
            return it.withNewerFlag(currentVersion)
        }
        latestRedirectRelease()?.let {
            Log.i(TAG, "release via redirect: ${it.latestVersion} apk=${it.apkUrl.isNotEmpty()}")
            return it.withNewerFlag(currentVersion)
        }
        // Both transports failed: no network, or both hosts unreachable.
        Log.w(TAG, "update check failed on both transports")
        return UpdateInfo("", "", "", false)
    }

    private fun UpdateInfo.withNewerFlag(currentVersion: String): UpdateInfo {
        val newer = latestVersion.isNotEmpty() && compareVersions(latestVersion, currentVersion) > 0
        return copy(isNewer = newer)
    }

    /** Path 1: the releases API. */
    private fun apiRelease(): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(RELEASES_API)
            if (conn.responseCode != 200) return null
            val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = obj.optString("tag_name", "").removePrefix("v")
            if (tag.isEmpty()) return null
            UpdateInfo(
                latestVersion = tag,
                releaseUrl = obj.optString("html_url", ""),
                apkUrl = findApkAsset(obj),
                isNewer = false,
            )
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Path 2: follow `github.com/<repo>/releases/latest`, which redirects to
     * `.../releases/tag/<tag>`. The tag comes from the redirect target, and the
     * asset URL follows GitHub's stable release-asset scheme.
     */
    private fun latestRedirectRelease(): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(LATEST_RELEASE_PAGE)
            // Do not follow redirects automatically: the Location header is the
            // answer, and the target page is ~200 KB of HTML we do not need.
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            val location = conn.getHeaderField("Location").orEmpty()
            if (code !in 300..399 || location.isEmpty()) return null

            val tag = location.substringAfterLast("/tag/", "").trim()
            if (tag.isEmpty()) return null

            val version = tag.removePrefix("v")
            val releaseUrl = location
            // GitHub names release assets predictably; verify before offering a
            // download so a missing APK falls back to opening the release page.
            val candidate = "https://github.com/$REPO/releases/download/$tag/dsh-remote-$version.apk"
            UpdateInfo(
                latestVersion = version,
                releaseUrl = releaseUrl,
                apkUrl = if (exists(candidate)) candidate else "",
                isNewer = false,
            )
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Whether [url] answers 200, without downloading it. */
    private fun exists(url: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url)
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            conn.responseCode == 200
        } catch (_: Exception) {
            false
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
