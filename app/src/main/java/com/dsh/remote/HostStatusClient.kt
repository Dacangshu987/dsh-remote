package com.dsh.remote

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the plugin's phone-facing pairing/transport snapshot at
 * `GET /api/pair/status` (the `/api/pair` route family, reachable from a paired
 * device). Used purely for diagnostics and user-facing messaging: the app
 * never gates access on it — the plugin's own 403 / re-scan page remains the
 * authority on pairing state.
 *
 * `phase` is one of `lan-required`, `stopped`, `waiting`, `connected`,
 * `disconnected` (see the plugin's `PairingPhase`).
 */
object HostStatusClient {

    /** One decoded snapshot; unknown fields stay null rather than failing. */
    data class HostStatus(
        val reachable: Boolean,
        val phase: String?,
        val deviceCount: Int?,
        val onlineCount: Int?,
        val tunnelState: String?,
        val relayState: String?,
        val lanAvailable: Boolean?,
    ) {
        companion object {
            fun unreachable() = HostStatus(false, null, null, null, null, null, null)
        }
    }

    /** Fetch the snapshot; never throws, returns [HostStatus.unreachable] on any failure. */
    fun fetch(host: String): HostStatus {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$host/api/pair/status").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "DSH-Remote-Android")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (conn.responseCode != 200) return HostStatus.unreachable()
            parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } catch (_: Exception) {
            HostStatus.unreachable()
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(json: JSONObject): HostStatus = HostStatus(
        reachable = true,
        phase = json.optString("phase").ifEmpty { null },
        deviceCount = json.optIntOrNull("deviceCount"),
        onlineCount = json.optIntOrNull("onlineCount"),
        tunnelState = json.optJSONObject("tunnel")?.optString("state")?.ifEmpty { null },
        relayState = json.optJSONObject("relay")?.optString("state")?.ifEmpty { null },
        lanAvailable = json.optBooleanOrNull("lanAvailable"),
    )

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
}
