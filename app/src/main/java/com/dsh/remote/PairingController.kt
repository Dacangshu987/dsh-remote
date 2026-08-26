package com.dsh.remote

import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native first-run pairing against the DSH remote-web-ui plugin.
 *
 * A pairing link looks like `http://<host>:<port>/m/?pair=<token>[&workspace=...]`.
 * Accepting it (POST /api/pair/accept with the token) makes the plugin set a
 * `dsh_pair` cookie that authorises every other /m/api request. We persist that
 * cookie into the shared WebView cookie store so the remote page works directly
 * after pairing — no need to open the in-page QR flow.
 */
object PairingController {

    /** A parsed pairing link. */
    data class PairTarget(
        val origin: String,   // e.g. http://192.168.1.100:3080
        val token: String,
        val workspace: String?,
    )

    /** Parse a pairing link (full URL, or a bare token) into a target. */
    fun parsePairLink(raw: String): PairTarget? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val url = URL(trimmed)
            val token = url.query?.split('&')?.firstOrNull { it.startsWith("pair=") }?.substringAfter("pair=")
            if (token.isNullOrEmpty()) return null
            val scheme = url.protocol.lowercase()
            val port = if (url.port != -1) ":${url.port}" else ""
            val origin = "$scheme://${url.host}$port"
            val workspace = url.query?.split('&')?.firstOrNull { it.startsWith("workspace=") }?.substringAfter("workspace=")
            PairTarget(origin, token, workspace?.ifEmpty { null })
        } catch (_: Exception) {
            null
        }
    }

    /** Outcome of checking the current pairing status against the host. */
    enum class PairingStatus { PAIRED, UNPAIRED, UNAVAILABLE }

    /**
     * Check whether this device is still paired with the host. Reads the
     * pairing cookie from the WebView store and asks `/api/pair/status`.
     * - PAIRED: cookie valid, device paired.
     * - UNPAIRED: host reachable but the cookie is missing/invalid (pairing lost).
     * - UNAVAILABLE: host unreachable (connection refused / network down).
     */
    fun pairingStatus(origin: String): PairingStatus {
        val base = origin.trim().trimEnd('/')
        val url = "$base/api/pair/status"
        val cookie = CookieManager.getInstance().getCookie(url)
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (_: Exception) {
            return PairingStatus.UNAVAILABLE
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val paired = JSONObject(body).optBoolean("paired", false)
                    if (paired) PairingStatus.PAIRED else PairingStatus.UNPAIRED
                }
                403 -> PairingStatus.UNPAIRED
                else -> PairingStatus.UNAVAILABLE
            }
        } catch (_: Exception) {
            PairingStatus.UNAVAILABLE
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Accept a pairing token on this device. On success the pairing cookie is
     * written into the WebView cookie store. Returns "ok" or an error message.
     */
    fun accept(origin: String, token: String): String {
        val url = origin.trimEnd('/') + "/api/pair/accept"
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            return "无法解析地址：${e.message ?: e.javaClass.simpleName}"
        }
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            val body = JSONObject().put("token", token).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            return when (conn.responseCode) {
                200 -> {
                    // Persist the Set-Cookie (dsh_pair=...) into the WebView store.
                    val setCookie = conn.getHeaderField("Set-Cookie")
                    if (!setCookie.isNullOrEmpty()) {
                        CookieManager.getInstance().setCookie(origin.trimEnd('/'), setCookie)
                    }
                    "ok"
                }
                404 -> "配对链接无效或已过期"
                409 -> "配对链接已被使用"
                403 -> "主机拒绝了此设备"
                else -> "HTTP ${conn.responseCode}"
            }
        } catch (e: Exception) {
            return "配对失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn.disconnect()
        }
    }
}
