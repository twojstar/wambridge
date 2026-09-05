package io.github.trvny.wambridge.mobile

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** How long to wait for the local stream server to accept a connection. */
private const val STREAM_REACHABILITY_TIMEOUT_MS = 2_000

/**
 * Refuse a stream URL nothing is listening on, before the speaker ever sees it.
 *
 * Measured on the physical M5 on 2026-08-28: a `SetUrlPlayback` aimed at an address the speaker
 * cannot pull wedges its control port, and the two commands that would recover it -
 * `SetUrlPlayback` and `SetStopPlayback` - are exactly the two that then stop answering, for the
 * full timeout, while `GetFunc` and `GetVolume` keep replying in 0.1 s. Only a power cycle clears
 * it. That makes refusing on the way in the only lever software still has.
 *
 * It connects and closes again **without sending a request**. Every URL offered here is served by
 * this phone's own proxy, and that proxy serves one consumer; a probe that asked for the body
 * would be a second one, which has broken a working stream before.
 */
internal fun assertStreamReachable(
    streamUrl: String,
    timeoutMs: Int = STREAM_REACHABILITY_TIMEOUT_MS,
    connect: (String, Int, Int) -> Unit = ::openAndClose,
) {
    val uri = try {
        URI(streamUrl)
    } catch (error: URISyntaxException) {
        throw IOException("Refusing to offer $streamUrl: it is not a URL (${error.reason})", error)
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw IOException("Refusing to offer $streamUrl: only http and https can be fetched.")
    }
    val host = uri.host
        ?: throw IOException("Refusing to offer $streamUrl: it names no host.")
    val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
    try {
        connect(host, port, timeoutMs)
    } catch (error: IOException) {
        throw IOException(
            "Refusing to offer $streamUrl: nothing is listening on $host:$port " +
                "(${error.message}). Handing this to the speaker would wedge its control " +
                "port until someone power-cycles it.",
            error,
        )
    }
}

private fun openAndClose(host: String, port: Int, timeoutMs: Int) {
    Socket().use { probe ->
        probe.connect(InetSocketAddress(host, port), timeoutMs)
    }
}

internal class SamsungWamChannel(
    context: Context,
    private val speakerIp: String,
    private val clientUuid: String,
    private val listener: Listener? = null,
    private val wifiTarget: WifiLan.Target? = null,
) : AutoCloseable {
    interface Listener {
        fun onPlaybackStarted()
        fun onReportedError(method: String?, code: String)
        fun onVolumeChanged(source: Any, raw: Int) {}
    }

    /** What `GetFunc` answered: the selected source and, on wifi, its submode. */
    data class FunctionState(val function: String?, val submode: String?)

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val sendLock = Any()
    private var socket: Socket? = null
    private var readerThread: Thread? = null

    fun connect() {
        synchronized(sendLock) {
            if (socket?.isConnected == true && socket?.isClosed == false) return

            val connection = (wifiTarget?.let {
                WifiLan.connectSocket(it, speakerIp, PORT, CONNECT_TIMEOUT_MS)
            } ?: WifiLan.connectSocket(appContext, speakerIp, PORT, CONNECT_TIMEOUT_MS)).apply {
                keepAlive = true
                soTimeout = READ_TIMEOUT_MS
            }
            socket = connection
            running.set(true)
            readerThread = Thread({ drainResponses(connection) }, "wam-mobile-control-reader").apply {
                isDaemon = true
                start()
            }
            send("GetFunc")
        }
    }

    fun offerStream(url: String) {
        assertStreamReachable(url)
        send(
            method = "SetUrlPlayback",
            arguments = listOf(
                Argument("url", url, Kind.CDATA),
                Argument("buffersize", "0", Kind.DEC),
                Argument("seektime", "0", Kind.DEC),
                Argument("resume", "0", Kind.DEC),
            ),
        )
    }

    fun pause() {
        send(
            method = "SetPlaybackControl",
            arguments = listOf(Argument("playbackcontrol", "pause", Kind.STR)),
            powerOn = true,
        )
    }

    fun resume() {
        send(
            method = "SetPlaybackControl",
            arguments = listOf(Argument("playbackcontrol", "resume", Kind.STR)),
            powerOn = true,
        )
    }

    fun selectFunction(function: String) {
        send(
            method = "SetFunc",
            arguments = listOf(Argument("function", function, Kind.STR)),
        )
    }

    fun setVolumeRaw(step: Int) {
        require(step in MIN_VOLUME_STEP..MAX_VOLUME_STEP) {
            "M5 volume step must be $MIN_VOLUME_STEP..$MAX_VOLUME_STEP"
        }
        send(
            method = "SetVolume",
            arguments = listOf(Argument("volume", step.toString(), Kind.DEC)),
            powerOn = true,
        )
    }

    fun setMute(muted: Boolean) {
        send(
            method = "SetMute",
            arguments = listOf(Argument("mute", if (muted) "on" else "off", Kind.STR)),
            powerOn = true,
        )
    }

    private fun send(
        method: String,
        arguments: List<Argument> = emptyList(),
        apiType: String = "UIC",
        powerOn: Boolean = false,
    ) {
        synchronized(sendLock) {
            if (socket?.isConnected != true || socket?.isClosed != false) connect()
            val activeSocket = requireNotNull(socket)
            val command = buildCommand(method, arguments, powerOn)
            val target = "/$apiType?cmd=${Uri.encode(command)}"
            val request = buildString {
                append("GET ").append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(speakerIp).append(':').append(PORT).append("\r\n")
                append("mobileUUID: ").append(clientUuid).append("\r\n")
                append("mobileName: Wireless Audio\r\n")
                append("mobileVersion: 1.0\r\n")
                append("Connection: keep-alive\r\n\r\n")
            }
            try {
                activeSocket.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
            } catch (error: IOException) {
                closeSocket()
                throw error
            }
        }
    }

    private fun drainResponses(activeSocket: Socket) {
        val bytes = ByteArray(8_192)
        val parser = ResponseParser()
        try {
            val input = BufferedInputStream(activeSocket.getInputStream())
            while (running.get() && !activeSocket.isClosed) {
                try {
                    val count = input.read(bytes)
                    if (count < 0) break
                    parser.feed(bytes, count).forEach(::handleResponseBody)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
            }
        } catch (_: IOException) {
            // The service reports command failures on the next send/reconnect attempt.
        } finally {
            if (socket === activeSocket) closeSocket()
        }
    }

    private fun handleResponseBody(body: String) {
        val method = METHOD_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
        val errorCode = RESPONSE_ERROR_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
            ?: ERROR_ELEMENT_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()

        if (!errorCode.isNullOrBlank() && errorCode !in SUCCESS_ERROR_CODES) {
            listener?.onReportedError(method, errorCode)
        }
        if (method.equals("StartPlaybackEvent", ignoreCase = true)) {
            listener?.onPlaybackStarted()
        }
        if (method.equals("VolumeLevel", ignoreCase = true)) {
            VOLUME_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in MIN_VOLUME_STEP..MAX_VOLUME_STEP }
                ?.let { listener?.onVolumeChanged(this, it) }
        }
    }

    override fun close() {
        running.set(false)
        closeSocket()
        readerThread?.interrupt()
        readerThread = null
    }

    private fun closeSocket() {
        synchronized(sendLock) {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Best effort during service teardown.
            }
            socket = null
        }
    }

    private fun buildCommand(
        method: String,
        arguments: List<Argument>,
        powerOn: Boolean,
    ): String = buildString {
        if (powerOn) append("<pwron>on</pwron>")
        append("<name>").append(xmlText(method)).append("</name>")
        for (argument in arguments) {
            when (argument.kind) {
                Kind.CDATA -> {
                    append("<p type=\"cdata\" name=\"")
                        .append(xmlAttribute(argument.name))
                        .append("\" val=\"empty\"><![CDATA[")
                        .append(argument.value.replace("]]>", "]]]]><![CDATA[>"))
                        .append("]]></p>")
                }

                Kind.STR, Kind.DEC -> {
                    append("<p type=\"")
                        .append(if (argument.kind == Kind.STR) "str" else "dec")
                        .append("\" name=\"")
                        .append(xmlAttribute(argument.name))
                        .append("\" val=\"")
                        .append(xmlAttribute(argument.value))
                        .append("\"/>")
                }
            }
        }
    }

    private fun xmlText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun xmlAttribute(value: String): String = xmlText(value)
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class Argument(val name: String, val value: String, val kind: Kind)
    private enum class Kind { STR, DEC, CDATA }

    private class ResponseParser {
        private var pending = ByteArray(0)

        fun feed(bytes: ByteArray, count: Int): List<String> {
            if (count <= 0) return emptyList()
            if (pending.size + count > MAX_PENDING_BYTES) {
                throw IOException("WAM response buffer exceeded limit")
            }
            pending += bytes.copyOf(count)

            val bodies = mutableListOf<String>()
            while (pending.isNotEmpty()) {
                val statusStart = indexOf(pending, HTTP_PREFIX)
                if (statusStart < 0) {
                    if (pending.size > HTTP_PREFIX.size) {
                        pending = pending.copyOfRange(pending.size - HTTP_PREFIX.size, pending.size)
                    }
                    break
                }
                if (statusStart > 0) pending = pending.copyOfRange(statusStart, pending.size)

                val headerEnd = indexOf(pending, HEADER_END)
                if (headerEnd < 0) break
                val headerText = String(pending, 0, headerEnd, StandardCharsets.ISO_8859_1)
                val status = STATUS_REGEX.find(headerText)?.groupValues?.getOrNull(1)
                    ?: throw IOException("WAM response missing HTTP status")
                val contentLength = CONTENT_LENGTH_REGEX.find(headerText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: throw IOException("WAM response missing Content-Length")
                if (contentLength !in 0..MAX_BODY_BYTES) {
                    throw IOException("WAM response body too large")
                }

                val bodyStart = headerEnd + HEADER_END.size
                val messageEnd = bodyStart + contentLength
                if (pending.size < messageEnd) break
                if (status == "200" && contentLength > 0) {
                    bodies += String(pending, bodyStart, contentLength, StandardCharsets.UTF_8)
                }
                pending = pending.copyOfRange(messageEnd, pending.size)
            }
            return bodies
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (start in 0..haystack.size - needle.size) {
                for (index in needle.indices) {
                    if (haystack[start + index] != needle[index]) continue@outer
                }
                return start
            }
            return -1
        }
    }

    companion object {
        private const val PORT = 55001
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 1_000
        private const val MAX_PENDING_BYTES = 1024 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private val HTTP_PREFIX = "HTTP/".toByteArray(StandardCharsets.US_ASCII)
        private val HEADER_END = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val STATUS_REGEX = Regex("^HTTP/1\\.[01]\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
        private val CONTENT_LENGTH_REGEX = Regex(
            "^Content-Length\\s*:\\s*(\\d+)\\s*$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        private val METHOD_REGEX = Regex(
            "<method>\\s*([^<]+?)\\s*</method>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val RESPONSE_ERROR_REGEX = Regex(
            "<response\\b[^>]*\\berrCode\\s*=\\s*['\"]([^'\"]+)['\"]",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val ERROR_ELEMENT_REGEX = Regex(
            "<errCode\\b[^>]*>\\s*([^<]+?)\\s*</errCode>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val SUCCESS_ERROR_CODES = setOf("0", "00", "000", "0000")
        // The speaker's own scale, not a percentage. UPnP speaks percent and
        // RendererService converts, which is why a phone player's slider moves in
        // dead zones - about 3.3% of travel per real step. Our own UI uses these.
        const val MIN_VOLUME_STEP = 0
        const val MAX_VOLUME_STEP = 30

        private val FUNCTION_REGEX = Regex("<function>([^<]*)</function>", RegexOption.IGNORE_CASE)
        private val SUBMODE_REGEX = Regex("<submode>([^<]*)</submode>", RegexOption.IGNORE_CASE)
        private val VOLUME_REGEX = Regex("<volume>([0-9]{1,3})</volume>", RegexOption.IGNORE_CASE)
        private val DEVICE_ID_REGEX = Regex("<device_id>([^<]+)</device_id>", RegexOption.IGNORE_CASE)

        fun newClientUuid(): String = UUID.randomUUID().toString()

        private fun httpConnections(
            context: Context,
            url: URL,
            wifiTarget: WifiLan.Target?,
        ): Sequence<HttpURLConnection> = if (wifiTarget == null) {
            WifiLan.openHttpConnections(context.applicationContext, url)
        } else {
            sequenceOf(WifiLan.openHttpConnection(wifiTarget, url))
        }

        /**
         * Read the selected source back with `GetFunc`. Commands sent over the
         * persistent channel are never acknowledged to the caller, so a refused
         * `SetFunc` is indistinguishable from an accepted one until this is read.
         */
        fun readFunction(context: Context, speakerIp: String): FunctionState {
            val command = Uri.encode("<name>GetFunc</name>")
            val url = URL("http://$speakerIp:$PORT/UIC?cmd=$command")
            var lastError: Exception? = null
            for (connection in WifiLan.openHttpConnections(context.applicationContext, url)) {
                connection.apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = CONNECT_TIMEOUT_MS
                    useCaches = false
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("WAM HTTP ${connection.responseCode}")
                    }
                    val body = connection.inputStream.use { it.bufferedReader().readText() }
                    return FunctionState(
                        function = FUNCTION_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim(),
                        submode = SUBMODE_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim(),
                    )
                } catch (error: Exception) {
                    lastError = error
                } finally {
                    connection.disconnect()
                }
            }
            throw lastError ?: IOException("No active Wi-Fi network")
        }

        /** Read the stable hardware identity used by desktop WAM Bridge profiles. */
        fun readDeviceId(
            context: Context,
            speakerIp: String,
            timeoutMs: Int = CONNECT_TIMEOUT_MS,
            wifiTarget: WifiLan.Target? = null,
        ): String {
            val command = Uri.encode("<name>GetDeviceId</name>")
            val url = URL("http://$speakerIp:$PORT/CPM?cmd=$command")
            var lastError: Exception? = null
            for (connection in httpConnections(context, url, wifiTarget)) {
                connection.apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    useCaches = false
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("WAM HTTP ${connection.responseCode}")
                    }
                    val body = connection.inputStream.use { it.bufferedReader().readText() }
                    val value = DEVICE_ID_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
                        ?: throw IOException("GetDeviceId returned no device_id")
                    return normalizeDeviceId(value)
                } catch (error: Exception) {
                    lastError = error
                } finally {
                    connection.disconnect()
                }
            }
            throw lastError ?: IOException("No active Wi-Fi network")
        }

        fun normalizeDeviceId(value: String): String =
            value.uppercase().filter(Char::isLetterOrDigit)

        /** Read the speaker's current raw volume step, on the same 0..30 scale it takes. */
        fun readVolumeRaw(context: Context, speakerIp: String): Int {
            val command = Uri.encode("<name>GetVolume</name>")
            val url = URL("http://$speakerIp:$PORT/UIC?cmd=$command")
            var lastError: Exception? = null
            for (connection in WifiLan.openHttpConnections(context.applicationContext, url)) {
                connection.apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = CONNECT_TIMEOUT_MS
                    useCaches = false
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("WAM HTTP ${connection.responseCode}")
                    }
                    val body = connection.inputStream.use { it.bufferedReader().readText() }
                    val value = VOLUME_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: throw IOException("GetVolume returned no volume")
                    return value.coerceIn(MIN_VOLUME_STEP, MAX_VOLUME_STEP)
                } catch (error: Exception) {
                    lastError = error
                } finally {
                    connection.disconnect()
                }
            }
            throw lastError ?: IOException("No active Wi-Fi network")
        }

        fun probe(
            context: Context,
            speakerIp: String,
            timeoutMs: Int = CONNECT_TIMEOUT_MS,
            wifiTarget: WifiLan.Target? = null,
        ): Boolean {
            val command = Uri.encode("<name>GetSpkName</name>")
            val url = URL("http://$speakerIp:$PORT/UIC?cmd=$command")
            for (connection in httpConnections(context, url, wifiTarget)) {
                connection.apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    useCaches = false
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) continue
                    connection.inputStream.use { input ->
                        if (input.bufferedReader().readText().isNotBlank()) return true
                    }
                } catch (_: IOException) {
                    // Try the next Wi-Fi network if one exists.
                } finally {
                    connection.disconnect()
                }
            }
            return false
        }
    }
}
