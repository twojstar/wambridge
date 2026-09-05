package io.github.trvny.wambridge.mobile

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class RadioProxyServer(
    context: Context,
    private val speakerIp: String,
    // Already ordered: a freshly resolved TuneIn stream first when the station
    // carries an id, the saved URLs behind it as the static fallbacks.
    private val sources: List<String>,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onStreamOpened(sourceUrl: String)
        fun onStreamClosed()
        fun onProxyError(message: String)
    }

    private data class OpenSource(
        val connection: HttpURLConnection,
        val contentType: String,
    )

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val clients = mutableSetOf<Socket>()
    private val clientLock = Any()
    private val path = "/radio/${UUID.randomUUID().toString().replace("-", "")}"
    private var server: ServerSocket? = null

    lateinit var localAddress: Inet4Address
        private set
    var networkHandle: Long = 0
        private set
    var port: Int = 0
        private set

    val url: String
        get() = "http://${localAddress.hostAddress}:$port$path"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val target = WifiLan.targets(appContext).firstOrNull()
                ?: error("No active Wi-Fi IPv4 address found")
            localAddress = target.address
            networkHandle = target.network.networkHandle
            val socket = ServerSocket(0, 8, localAddress)
            server = socket
            port = socket.localPort
            Thread({ acceptLoop(socket) }, "wam-radio-proxy").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                synchronized(clientLock) { clients += client }
                executor.execute {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(clientLock) { clients -= client }
                        runCatching { client.close() }
                    }
                }
            } catch (_: IOException) {
                if (running.get()) listener.onProxyError("Radio proxy listener stopped")
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val request = readRequestLine(input)
        val speakerPeer = client.inetAddress.hostAddress == speakerIp
        if (!speakerPeer || request.first != "GET" || request.second != path) {
            writeError(output, 403, "Forbidden")
            return
        }

        var lastError: Exception? = null
        for (source in sources) {
            val opened = try {
                openSource(source)
            } catch (error: Exception) {
                lastError = error
                continue
            }

            writeSuccess(output, opened.contentType)
            listener.onStreamOpened(source)
            try {
                opened.connection.inputStream.use { raw ->
                    BufferedInputStream(raw, COPY_BUFFER).copyTo(output, COPY_BUFFER)
                    output.flush()
                }
            } catch (error: Exception) {
                listener.onProxyError(error.message ?: error.javaClass.simpleName)
            } finally {
                opened.connection.disconnect()
                listener.onStreamClosed()
            }
            return
        }

        val message = lastError?.message ?: "No usable station URL"
        listener.onProxyError(message)
        runCatching { writeError(output, 502, "Bad Gateway") }
    }

    private fun openSource(source: String): OpenSource {
        val uri = URI(source)
        require(!uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8")) {
            "HLS radio streams are not supported by the mobile relay yet"
        }

        var lastError: Exception? = null
        val sourceUrl = URL(source)
        for (connection in WifiLan.openHttpConnections(appContext, sourceUrl)) {
            connection.apply {
                connectTimeout = SOURCE_CONNECT_TIMEOUT_MS
                readTimeout = SOURCE_READ_TIMEOUT_MS
                useCaches = false
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WAMBridge-Mobile/0.1")
                setRequestProperty("Icy-MetaData", "0")
            }
            try {
                connection.connect()
                require(connection.responseCode in 200..299) {
                    "Radio source HTTP ${connection.responseCode}"
                }
                val contentType = connection.contentType.orEmpty()
                    .substringBefore(';')
                    .trim()
                    .lowercase(Locale.ROOT)
                require(contentType !in HLS_TYPES) {
                    "HLS radio streams are not supported by the mobile relay yet"
                }
                require(contentType !in OGG_TYPES) {
                    "Ogg radio needs transcoding and is not supported by the mobile relay yet"
                }
                return OpenSource(
                    connection = connection,
                    contentType = contentType.ifBlank { "application/octet-stream" },
                )
            } catch (error: Exception) {
                lastError = error
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("No active Wi-Fi network")
    }

    private fun readRequestLine(input: BufferedInputStream): Pair<String, String> {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw IOException("Radio proxy request ended before headers")
            val byte = value.toByte()
            bytes += byte
            matched = if (byte == end[matched]) matched + 1 else if (byte == end[0]) 1 else 0
            if (matched == end.size) break
        }
        require(matched == end.size) { "Radio proxy request headers too large" }
        val header = bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
        val first = header.substringBefore("\r\n").split(' ', limit = 3)
        require(first.size >= 2) { "Invalid radio proxy request" }
        return first[0].uppercase(Locale.ROOT) to first[1].substringBefore('?')
    }

    private fun writeSuccess(output: BufferedOutputStream, contentType: String) {
        output.write(
            buildString {
                append("HTTP/1.0 200 OK\r\n")
                append("Content-Type: $contentType\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1),
        )
        output.flush()
    }

    private fun writeError(output: BufferedOutputStream, status: Int, reason: String) {
        val body = reason.toByteArray(StandardCharsets.UTF_8)
        output.write(
            buildString {
                append("HTTP/1.0 $status $reason\r\n")
                append("Content-Type: text/plain\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1),
        )
        output.write(body)
        output.flush()
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        server = null
        synchronized(clientLock) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        executor.shutdownNow()
    }

    companion object {
        private const val CLIENT_TIMEOUT_MS = 15_000
        private const val SOURCE_CONNECT_TIMEOUT_MS = 7_000
        private const val SOURCE_READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val COPY_BUFFER = 64 * 1024
        private val HLS_TYPES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
        )
        private val OGG_TYPES = setOf("audio/ogg", "application/ogg")
    }
}
