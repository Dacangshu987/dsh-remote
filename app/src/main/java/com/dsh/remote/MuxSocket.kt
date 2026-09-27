package com.dsh.remote

import android.util.Base64
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Hand-rolled RFC 6455 WebSocket client — enough of it for the DSH gateway
 * mux (`/remote/api/remote.mux`), which speaks text frames only. Written by
 * hand so the app does not grow an HTTP/WebSocket dependency for one socket.
 *
 * Usage: [connect] performs the HTTP upgrade and returns a [Session] whose
 * [Session.readText] blocks until a text message arrives and [Session.sendText]
 * writes one. Ping is answered transparently; a close frame raises
 * [ClosedException] so the caller reconnects.
 */
object MuxSocket {

    /** The socket closed normally or remotely; the caller should reconnect. */
    class ClosedException(message: String) : Exception(message)

    class Session internal constructor(
        private val socket: Socket,
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        /**
         * Block until one text message arrives; returns null for frames that
         * carry no application payload (ping/pong handled internally).
         */
        /**
         * Block until one text message arrives; returns null for frames that
         * carry no application payload (ping/pong handled internally).
         *
         * @throws java.net.SocketTimeoutException when nothing arrives within
         *   the socket's read timeout. That is deliberate: a half-open
         *   connection (the phone slept, or the network changed underneath)
         *   never reports an error on its own, so a blocking read with no
         *   timeout leaves the caller believing it is connected to a socket
         *   that can never deliver again. The caller treats the timeout as a
         *   dead link and reconnects.
         */
        fun readText(): String? {
            while (true) {
                val first = input.read()
                if (first < 0) throw ClosedException("stream ended")
                val opcode = first and 0x0F

                val second = input.read()
                if (second < 0) throw EOFException("truncated frame header")
                val masked = second and 0x80 != 0
                var length = (second and 0x7F).toLong()

                if (length == 126L) length = readUnsigned(input, 2)
                else if (length == 127L) length = readUnsigned(input, 8)

                val maskKey = if (masked) readFully(input, 4) else null
                val payload = readFully(input, length.toInt())
                if (maskKey != null) {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }

                when (opcode) {
                    0x1 -> return payload.toString(Charsets.UTF_8)
                    0x8 -> throw ClosedException("close frame received")
                    0x9 -> sendFrame(0xA, payload) // pong
                    0xA, 0x0 -> Unit // pong / continuation of a text frame we do not expect
                    else -> Unit
                }
            }
        }

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

        fun close() {
            try {
                sendFrame(0x8, ByteArray(0))
            } catch (_: Exception) {
                // Best effort: the caller is tearing the socket down anyway.
            }
            try {
                socket.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }

        @Synchronized
        private fun sendFrame(opcode: Int, payload: ByteArray) {
            val header = ArrayList<Byte>(payload.size + 14)
            header.add((0x80 or opcode).toByte()) // FIN + opcode

            val maskBit = 0x80
            when {
                payload.size < 126 -> header.add((maskBit or payload.size).toByte())
                payload.size <= 0xFFFF -> {
                    header.add((maskBit or 126).toByte())
                    header.add(((payload.size shr 8) and 0xFF).toByte())
                    header.add((payload.size and 0xFF).toByte())
                }
                else -> {
                    header.add((maskBit or 127).toByte())
                    for (shift in 56 downTo 0 step 8) {
                        header.add(((payload.size.toLong() shr shift) and 0xFF).toByte())
                    }
                }
            }

            val mask = ByteArray(4).also { RANDOM.nextBytes(it) }
            header.addAll(mask.toList())

            val frame = ByteArray(header.size + payload.size)
            for (i in header.indices) frame[i] = header[i]
            for (i in payload.indices) {
                frame[header.size + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            output.write(frame)
            output.flush()
        }
    }

    private val RANDOM = SecureRandom()

    /**
     * How long a read may stay silent before the link is presumed dead.
     *
     * The host pushes an event only when something happens in a session, so
     * silence is normal; this only has to be short enough that a half-open
     * socket (phone slept, network changed) is noticed and reconnected in
     * reasonable time.
     */
    private const val READ_TIMEOUT_MS = 120_000

    /**
     * Open one upgraded socket.
     *
     * @param url - `ws://` or `wss://` URL (the caller converts an http(s) host
     *   into its ws(s) form and appends the credential query).
     * @param timeoutMs - TCP connect and handshake read timeout.
     */
    fun connect(url: String, timeoutMs: Int = 10_000): Session {
        val uri = java.net.URI(url)
        val secure = uri.scheme.equals("wss", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host ?: throw IllegalArgumentException("no host in $url")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val path = buildString {
            append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        val socket: Socket = if (secure) {
            // A relay / tunnel host is https, so its mux is wss. Trust comes from
            // the platform store — the same CAs the WebView already trusts for
            // that origin. A self-signed https LAN host is unsupported by design.
            (javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket).apply {
                connect(InetSocketAddress(host, port), timeoutMs)
                startHandshake()
            }
        } else {
            Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
            }
        }
        socket.tcpNoDelay = true
        socket.soTimeout = 0 // long-lived reads block indefinitely by design

        val out = socket.getOutputStream()
        val key = ByteArray(16).also { RANDOM.nextBytes(it) }
        val keyHeader = Base64.encodeToString(key, Base64.NO_WRAP)

        val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(keyHeader).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: DSH-Remote-Android\r\n")
            append("\r\n")
        }
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()

        val input = socket.getInputStream()
        val responseHead = readHttpHead(input)
        val statusLine = responseHead.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 101")) {
            socket.close()
            throw ClosedException("handshake rejected: ${statusLine.trim()}")
        }
        // Frames arrive only when the host has an event, which can be minutes
        // apart, so this is a liveness ceiling rather than an expected cadence:
        // past it the link is presumed dead and the service reconnects.
        socket.soTimeout = READ_TIMEOUT_MS
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (keyHeader + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)
            ),
            Base64.NO_WRAP,
        )
        val accept = responseHead.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accept != expected) {
            socket.close()
            throw ClosedException("handshake accept mismatch")
        }

        return Session(socket, input, out)
    }

    /**
     * One-shot wire check for the whole socket path: connect, open the
     * forwarded-event stream, and wait for the gateway's `ready` frame.
     *
     * Used only by the loopback debug receiver, so a handset can prove that
     * `ws://`/`wss://` + handshake + `$events` work on a real device instead of
     * relying on the logs of a background service.
     *
     * @returns a human-readable result; never throws.
     */
    fun selfCheck(url: String, timeoutMs: Int = 15_000): String {
        var session: Session? = null
        return try {
            session = connect(url, timeoutMs)
            val streamId = java.util.UUID.randomUUID().toString()
            session.sendText(
                org.json.JSONObject()
                    .put("type", "open")
                    .put("streamId", streamId)
                    .put("endpoint", "\$events")
                    .put("payload", org.json.JSONObject().put("args", org.json.JSONObject()))
                    .toString()
            )
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val text = session.readText() ?: continue
                val frame = org.json.JSONObject(text)
                if (frame.optString("streamId") != streamId) continue
                val value = frame.optJSONObject("value") ?: continue
                when (value.optString("type")) {
                    "ready" -> return "OK ready clientId=${value.optString("clientId").take(8)}"
                    "error" -> return "ERR ${value.optJSONObject("error")}"
                }
            }
            "ERR no ready frame within ${timeoutMs}ms"
        } catch (e: Exception) {
            "ERR ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            session?.close()
        }
    }

    /** Read headers up to the blank line, leaving the stream at the first frame byte. */
    private fun readHttpHead(input: InputStream): String {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("ended during handshake")
            buffer.append(b.toChar())
            if (buffer.length >= 4 && buffer.endsWith("\r\n\r\n")) return buffer.toString()
            if (buffer.length > 16_384) throw IllegalStateException("handshake header too large")
        }
    }

    private fun readUnsigned(input: InputStream, bytes: Int): Long {
        var value = 0L
        repeat(bytes) {
            val b = input.read()
            if (b < 0) throw EOFException("truncated length")
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw EOFException("truncated payload")
            read += n
        }
        return buffer
    }
}
