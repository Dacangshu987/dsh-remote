package com.dsh.remote

import java.net.URL

/**
 * First-run pairing against the DSH remote-web-ui plugin (0.3.x).
 *
 * A pairing link is the QR content minted by the desktop remote panel and
 * looks like `http://<host>:<port>/pair-accept?pair=<token>`. The app only
 * parses the link to extract the host base URL (persisted) and the full
 * pairing URL (loaded in the WebView); the accept handshake itself is done
 * in-browser by the plugin (`/pair-accept` -> `/pair-app` -> `/`).
 */
object PairingController {

    /** A parsed pairing link. */
    data class PairTarget(
        val origin: String,  // e.g. http://192.168.1.100:3080
        val pairUrl: String, // full link to open in the WebView
        val token: String,
    )

    /** Parse a pairing link into its origin + the URL to load. */
    fun parsePairLink(raw: String): PairTarget? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val url = URL(trimmed)
            val token = url.query
                ?.split('&')
                ?.firstOrNull { it.startsWith("pair=") }
                ?.substringAfter("pair=")
            if (token.isNullOrEmpty()) return null
            val scheme = url.protocol.lowercase()
            val port = if (url.port != -1) ":${url.port}" else ""
            val origin = "$scheme://${url.host}$port"
            PairTarget(origin, trimmed, token)
        } catch (_: Exception) {
            null
        }
    }
}
