package io.github.trvny.wambridge.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface RendererCallbacks {
    fun onPlay(rendererStreamUrl: String)
    fun onStreamOpened()
    fun onStreamClosed()
    fun onPause()
    fun onStop()
    fun onVolume(percent: Int)
    fun onMute(muted: Boolean)
}

internal class RendererState(val udn: String) {
    @Volatile var currentUri = ""
    @Volatile var currentMetadata = ""
    @Volatile var nextUri = ""
    @Volatile var nextMetadata = ""
    @Volatile var transportState = "STOPPED"
    @Volatile var volumePercent = 20
    @Volatile var muted = false
    @Volatile var lastError = ""
}

internal class UpnpRenderer(
    private val context: Context,
    private val state: RendererState,
    private val callbacks: RendererCallbacks,
    private val speakerIp: String,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val streamSources = ConcurrentHashMap<Socket, HttpURLConnection>()
    private val activeStream = AtomicReference<Socket?>(null)
    private val streamPath = "/stream/${UUID.randomUUID().toString().replace("-", "")}"
    private var httpServer: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    lateinit var localAddress: Inet4Address
        private set
    var networkHandle: Long = 0
        private set
    var port: Int = 0
        private set

    val streamUrl: String
        get() = "http://${localAddress.hostAddress}:$port$streamPath"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val target = WifiLan.targets(context).firstOrNull()
                ?: error("No active Wi-Fi IPv4 address found")
            localAddress = target.address
            networkHandle = target.network.networkHandle
            startHttp()
            startSsdp()
        } catch (error: Exception) {
            running.set(false)
            closeResources()
            throw error
        }
    }

    private fun startHttp() {
        val server = ServerSocket(0, 50, localAddress)
        httpServer = server
        port = server.localPort
        Thread({
            while (running.get()) {
                try {
                    val socket = server.accept()
                    clientSockets += socket
                    executor.execute {
                        try {
                            handleClient(socket)
                        } finally {
                            clientSockets -= socket
                            try { socket.close() } catch (_: Exception) { }
                        }
                    }
                } catch (_: IOException) {
                    if (running.get()) state.lastError = "UPnP HTTP listener stopped"
                }
            }
        }, "wam-upnp-http").apply {
            isDaemon = true
            start()
        }
    }

    private fun startSsdp() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi.createMulticastLock("wambridge-upnp").apply {
            setReferenceCounted(false)
            acquire()
        }

        val lanInterface = NetworkInterface.getByInetAddress(localAddress)
            ?: error("No interface for ${localAddress.hostAddress}")
        val group = InetAddress.getByName(SSDP_GROUP)
        val socket = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(SSDP_PORT))
            this.networkInterface = lanInterface
            joinGroup(InetSocketAddress(group, SSDP_PORT), lanInterface)
            soTimeout = 1000
        }
        ssdpSocket = socket
        Thread({ ssdpLoop(socket) }, "wam-upnp-ssdp").apply {
            isDaemon = true
            start()
        }
        // Do not wait for a control point to happen to issue a fresh M-SEARCH.
        // Announce the renderer as soon as it comes up; old players are much
        // happier when the UPnP lifecycle actually has a beginning.
        repeat(2) { sendAlive(socket) }
    }

    private fun ssdpLoop(socket: MulticastSocket) {
        val buffer = ByteArray(32 * 1024)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
                val headers = parseHeaders(text)
                if (!headers["man"].orEmpty().contains("ssdp:discover", ignoreCase = true)) continue
                val requested = headers["st"].orEmpty().trim()
                val targets = when {
                    requested.equals("ssdp:all", ignoreCase = true) -> advertisedTargets()
                    requested.equals("uuid:${state.udn}", ignoreCase = true) -> listOf("uuid:${state.udn}")
                    SSDP_TARGETS.any { it.equals(requested, ignoreCase = true) } -> listOf(requested)
                    else -> emptyList()
                }
                targets.forEach { sendSearchResponse(socket, packet, it) }
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (running.get()) state.lastError = "SSDP listener stopped"
            }
        }
    }

    private fun sendSearchResponse(socket: MulticastSocket, request: DatagramPacket, target: String) {
        val bytes = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("EXT:\r\n")
            append("LOCATION: http://${localAddress.hostAddress}:$port/description.xml\r\n")
            append("SERVER: $SERVER_HEADER\r\n")
            append("ST: $target\r\n")
            append("USN: ${SsdpLifecycle.usn(state.udn, target)}\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, request.address, request.port))
    }

    private fun advertisedTargets(): List<String> =
        SsdpLifecycle.advertisedTargets(state.udn, SSDP_TARGETS)

    private fun sendAlive(socket: MulticastSocket) {
        val location = "http://${localAddress.hostAddress}:$port/description.xml"
        for (target in advertisedTargets()) {
            val bytes = SsdpLifecycle.alive(SSDP_HOST, location, SERVER_HEADER, state.udn, target)
            socket.send(DatagramPacket(bytes, bytes.size, SSDP_ADDRESS, SSDP_PORT))
        }
    }

    private fun sendByebye() {
        val socket = ssdpSocket ?: return
        for (target in advertisedTargets()) {
            runCatching {
                val bytes = SsdpLifecycle.byebye(SSDP_HOST, state.udn, target)
                socket.send(DatagramPacket(bytes, bytes.size, SSDP_ADDRESS, SSDP_PORT))
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = CLIENT_TIMEOUT_MS
                val request = readRequest(BufferedInputStream(socket.getInputStream()))
                val output = BufferedOutputStream(socket.getOutputStream())
                // Who may reach what is decided in RendererRouting, away from the
                // socket, so it can be tested. Nothing below re-checks the peer.
                val route = RendererRouting.route(
                    method = request.method,
                    path = request.path,
                    peer = socket.inetAddress,
                    streamPath = streamPath,
                    speakerIp = speakerIp,
                    localAddress = localAddress,
                )
                when (route) {
                    is RendererRoute.Denied ->
                        textResponse(output, 403, "text/plain", route.message)
                    RendererRoute.NotFound ->
                        textResponse(output, 404, "text/plain", "Not found")
                    is RendererRoute.Allowed -> when (route.endpoint) {
                        RendererEndpoint.STREAM -> proxyStream(socket, output)
                        RendererEndpoint.DESCRIPTION ->
                            textResponse(output, 200, "text/xml; charset=utf-8", descriptionXml())
                        RendererEndpoint.ICON -> iconResponse(output)
                        RendererEndpoint.AV_TRANSPORT_SCPD ->
                            textResponse(output, 200, "text/xml; charset=utf-8", avTransportScpd())
                        RendererEndpoint.RENDERING_CONTROL_SCPD ->
                            textResponse(
                                output,
                                200,
                                "text/xml; charset=utf-8",
                                renderingControlScpd(),
                            )
                        RendererEndpoint.CONNECTION_MANAGER_SCPD ->
                            textResponse(
                                output,
                                200,
                                "text/xml; charset=utf-8",
                                connectionManagerScpd(),
                            )
                        RendererEndpoint.AV_TRANSPORT_CONTROL -> handleAvTransport(output, request)
                        RendererEndpoint.RENDERING_CONTROL_CONTROL ->
                            handleRenderingControl(output, request)
                        RendererEndpoint.CONNECTION_MANAGER_CONTROL ->
                            handleConnectionManager(output, request)
                        RendererEndpoint.SUBSCRIBE -> subscriptionResponse(output, request)
                        RendererEndpoint.UNSUBSCRIBE ->
                            rawResponse(output, 200, "OK", emptyMap(), ByteArray(0))
                    }
                }
            } catch (error: Exception) {
                state.lastError = error.message ?: error.javaClass.simpleName
                try {
                    textResponse(
                        BufferedOutputStream(socket.getOutputStream()),
                        500,
                        "text/plain",
                        state.lastError,
                    )
                } catch (_: Exception) {
                    // Peer already disconnected.
                }
            }
        }
    }

    private fun handleAvTransport(out: BufferedOutputStream, request: HttpRequest) {
        val action = soapAction(request)
        when (action) {
            "SetAVTransportURI" -> {
                state.currentUri = soapValue(request.body, "CurrentURI")
                state.currentMetadata = soapValue(request.body, "CurrentURIMetaData")
                state.transportState = "STOPPED"
                state.lastError = ""
                soapOk(out, AV_TRANSPORT, action)
            }
            "SetNextAVTransportURI" -> {
                state.nextUri = soapValue(request.body, "NextURI")
                state.nextMetadata = soapValue(request.body, "NextURIMetaData")
                soapOk(out, AV_TRANSPORT, action)
            }
            "Play" -> {
                require(state.currentUri.isNotBlank()) { "No CurrentURI" }
                callbacks.onPlay(streamUrl)
                state.transportState = "TRANSITIONING"
                soapOk(out, AV_TRANSPORT, action)
            }
            "Pause" -> {
                callbacks.onPause()
                state.transportState = "PAUSED_PLAYBACK"
                soapOk(out, AV_TRANSPORT, action)
            }
            "Stop" -> {
                callbacks.onStop()
                state.transportState = "STOPPED"
                soapOk(out, AV_TRANSPORT, action)
            }
            "GetTransportInfo" -> soapOk(
                out,
                AV_TRANSPORT,
                action,
                "<CurrentTransportState>${state.transportState}</CurrentTransportState>" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>",
            )
            "GetMediaInfo" -> soapOk(
                out,
                AV_TRANSPORT,
                action,
                "<NrTracks>1</NrTracks><MediaDuration>00:00:00</MediaDuration>" +
                    "<CurrentURI>${xml(state.currentUri)}</CurrentURI>" +
                    "<CurrentURIMetaData>${xml(state.currentMetadata)}</CurrentURIMetaData>" +
                    "<NextURI>${xml(state.nextUri)}</NextURI>" +
                    "<NextURIMetaData>${xml(state.nextMetadata)}</NextURIMetaData>" +
                    "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                    "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>",
            )
            "GetPositionInfo" -> soapOk(
                out,
                AV_TRANSPORT,
                action,
                "<Track>1</Track><TrackDuration>00:00:00</TrackDuration>" +
                    "<TrackMetaData>${xml(state.currentMetadata)}</TrackMetaData>" +
                    "<TrackURI>${xml(state.currentUri)}</TrackURI>" +
                    "<RelTime>00:00:00</RelTime><AbsTime>00:00:00</AbsTime>" +
                    "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>",
            )
            "GetTransportSettings" -> soapOk(
                out,
                AV_TRANSPORT,
                action,
                "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>",
            )
            "Seek", "SetPlayMode" -> soapOk(out, AV_TRANSPORT, action)
            else -> soapError(out, 401, "Invalid Action")
        }
    }

    private fun handleRenderingControl(out: BufferedOutputStream, request: HttpRequest) {
        val action = soapAction(request)
        when (action) {
            "SetVolume" -> {
                val volume = soapValue(request.body, "DesiredVolume").toInt().coerceIn(0, 100)
                callbacks.onVolume(volume)
                state.volumePercent = volume
                soapOk(out, RENDERING_CONTROL, action)
            }
            "GetVolume" -> soapOk(
                out,
                RENDERING_CONTROL,
                action,
                "<CurrentVolume>${state.volumePercent}</CurrentVolume>",
            )
            "SetMute" -> {
                val value = soapValue(request.body, "DesiredMute").lowercase(Locale.ROOT)
                val muted = value == "1" || value == "true"
                callbacks.onMute(muted)
                state.muted = muted
                soapOk(out, RENDERING_CONTROL, action)
            }
            "GetMute" -> soapOk(
                out,
                RENDERING_CONTROL,
                action,
                "<CurrentMute>${if (state.muted) 1 else 0}</CurrentMute>",
            )
            "ListPresets" -> soapOk(
                out,
                RENDERING_CONTROL,
                action,
                "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>",
            )
            "SelectPreset" -> soapOk(out, RENDERING_CONTROL, action)
            else -> soapError(out, 401, "Invalid Action")
        }
    }

    private fun handleConnectionManager(out: BufferedOutputStream, request: HttpRequest) {
        val action = soapAction(request)
        when (action) {
            "GetProtocolInfo" -> soapOk(
                out,
                CONNECTION_MANAGER,
                action,
                "<Source></Source><Sink>${xml(SINK_PROTOCOLS)}</Sink>",
            )
            "GetCurrentConnectionIDs" -> soapOk(
                out,
                CONNECTION_MANAGER,
                action,
                "<ConnectionIDs>0</ConnectionIDs>",
            )
            "GetCurrentConnectionInfo" -> soapOk(
                out,
                CONNECTION_MANAGER,
                action,
                "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>" +
                    "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>" +
                    "<PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>",
            )
            else -> soapError(out, 401, "Invalid Action")
        }
    }

    private fun proxyStream(client: Socket, out: BufferedOutputStream) {
        val previous = activeStream.getAndSet(client)
        if (previous != null && previous !== client) {
            streamSources.remove(previous)?.disconnect()
            try { previous.close() } catch (_: Exception) { }
        }

        callbacks.onStreamOpened()
        try {
            val source = state.currentUri
            require(isLocalPlayerUri(source)) { "Only this phone's HTTP sources are accepted" }
            val connection = (URI(source).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = SOURCE_CONNECT_TIMEOUT_MS
                readTimeout = SOURCE_READ_TIMEOUT_MS
                useCaches = false
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "WAMBridge-Mobile/0.1")
            }
            streamSources[client] = connection
            try {
                connection.connect()
                require(connection.responseCode in 200..299) {
                    "UPnP source HTTP ${connection.responseCode}"
                }
                val contentType = connection.contentType.orEmpty()
                val baseType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
                val l16 = baseType == "audio/l16"
                val outgoing = if (l16) "audio/wav" else contentType.ifBlank { "application/octet-stream" }
                headers(
                    out,
                    200,
                    "OK",
                    mapOf("Content-Type" to outgoing, "Connection" to "close", "Cache-Control" to "no-store"),
                )
                connection.inputStream.use { raw ->
                    val input = BufferedInputStream(raw, 64 * 1024)
                    if (l16) {
                        val rate = parameter(contentType, "rate")?.toIntOrNull() ?: 44_100
                        val channels = parameter(contentType, "channels")?.toIntOrNull() ?: 2
                        out.write(wavHeader(rate, channels, 16))
                        copyL16(input, out)
                    } else {
                        input.copyTo(out, 64 * 1024)
                    }
                    out.flush()
                }
            } finally {
                streamSources.remove(client)
                connection.disconnect()
            }
        } finally {
            if (activeStream.compareAndSet(client, null)) {
                if (state.nextUri.isNotBlank()) {
                    state.currentUri = state.nextUri
                    state.currentMetadata = state.nextMetadata
                    state.nextUri = ""
                    state.nextMetadata = ""
                }
                callbacks.onStreamClosed()
            }
        }
    }

    private fun copyL16(input: BufferedInputStream, out: BufferedOutputStream) {
        val buffer = ByteArray(64 * 1024)
        var carry: Byte? = null
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            var start = 0

            carry?.let { high ->
                if (count > 0) {
                    out.write(byteArrayOf(buffer[0], high))
                    carry = null
                    start = 1
                }
            }

            var index = start
            while (index + 1 < count) {
                val high = buffer[index]
                buffer[index] = buffer[index + 1]
                buffer[index + 1] = high
                index += 2
            }
            if (index > start) out.write(buffer, start, index - start)
            if (index < count) carry = buffer[index]
        }
    }

    private fun wavHeader(rate: Int, channels: Int, bits: Int): ByteArray {
        val bytesPerSample = bits / 8
        val byteRate = rate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        return ByteArrayOutputStream(44).apply {
            write("RIFF".toByteArray(StandardCharsets.US_ASCII)); le32(0xffffffffL)
            write("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII)); le32(16)
            le16(1); le16(channels); le32(rate.toLong()); le32(byteRate.toLong())
            le16(blockAlign); le16(bits)
            write("data".toByteArray(StandardCharsets.US_ASCII)); le32(0xffffffffL)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.le32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private fun isLocalPlayerUri(value: String): Boolean = try {
        val uri = URI(value)
        if (!uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrBlank()) false
        else InetAddress.getAllByName(uri.host).all { address ->
            address.isLoopbackAddress || address.hostAddress == localAddress.hostAddress
        }
    } catch (_: Exception) {
        false
    }

    private fun parameter(contentType: String, name: String): String? = contentType
        .split(';')
        .drop(1)
        .map(String::trim)
        .firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.trim()
        ?.trim('"')

    private fun iconResponse(out: BufferedOutputStream) {
        val drawable = context.getDrawable(R.drawable.ic_launcher_foreground)
            ?: error("Renderer icon drawable unavailable")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        val bytes = ByteArrayOutputStream().use { buffer ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)) { "Could not encode renderer icon" }
            buffer.toByteArray()
        }
        bitmap.recycle()
        rawResponse(
            out,
            200,
            "OK",
            mapOf(
                "Content-Type" to "image/png",
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "public, max-age=86400",
            ),
            bytes,
        )
    }

    private fun subscriptionResponse(out: BufferedOutputStream, request: HttpRequest) {
        rawResponse(
            out,
            200,
            "OK",
            mapOf(
                "SID" to (request.headers["sid"] ?: "uuid:${UUID.randomUUID()}"),
                "TIMEOUT" to "Second-1800",
            ),
            ByteArray(0),
        )
    }

    private fun soapAction(request: HttpRequest): String = request.headers["soapaction"]
        ?.trim()
        ?.trim('"')
        ?.substringAfterLast('#')
        ?.takeIf(String::isNotBlank)
        ?: error("Missing SOAPAction")

    private fun soapValue(body: String, name: String): String {
        val regex = Regex(
            "<(?:[A-Za-z0-9_.-]+:)?${Regex.escape(name)}(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?${Regex.escape(name)}>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return unxml(regex.find(body)?.groupValues?.get(1).orEmpty().trim())
    }

    private fun unxml(value: String): String = value
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
        .replace("&quot;", "\"", true)
        .replace("&apos;", "'", true)
        .replace("&amp;", "&", true)

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun soapOk(out: BufferedOutputStream, service: String, action: String, inner: String = "") {
        textResponse(
            out,
            200,
            "text/xml; charset=utf-8",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:${action}Response xmlns:u=\"$service\">$inner</u:${action}Response>" +
                "</s:Body></s:Envelope>",
        )
    }

    private fun soapError(out: BufferedOutputStream, code: Int, description: String) {
        textResponse(
            out,
            500,
            "text/xml; charset=utf-8",
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
                "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
                "<errorCode>$code</errorCode><errorDescription>${xml(description)}</errorDescription>" +
                "</UPnPError></detail></s:Fault></s:Body></s:Envelope>",
        )
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest {
        val header = ByteArrayOutputStream()
        var stateIndex = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (header.size() < MAX_HEADER) {
            val next = input.read()
            require(next >= 0) { "Connection closed before headers" }
            header.write(next)
            stateIndex = if (next.toByte() == end[stateIndex]) stateIndex + 1 else if (next == 13) 1 else 0
            if (stateIndex == 4) break
        }
        require(stateIndex == 4) { "HTTP headers too large" }
        val lines = header.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n")
        val first = lines.first().split(' ', limit = 3)
        require(first.size >= 2) { "Bad HTTP request line" }
        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                    line.substring(separator + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..MAX_BODY) { "HTTP request body too large" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            require(count >= 0) { "Incomplete HTTP body" }
            read += count
        }
        return HttpRequest(
            first[0].uppercase(Locale.ROOT),
            first[1].substringBefore('?'),
            headers,
            String(body, StandardCharsets.UTF_8),
        )
    }

    private fun textResponse(out: BufferedOutputStream, status: Int, type: String, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        rawResponse(
            out,
            status,
            when (status) {
                200 -> "OK"
                403 -> "Forbidden"
                404 -> "Not Found"
                503 -> "Service Unavailable"
                else -> "Error"
            },
            mapOf("Content-Type" to type, "Content-Length" to body.size.toString()),
            body,
        )
    }

    private fun rawResponse(
        out: BufferedOutputStream,
        status: Int,
        reason: String,
        responseHeaders: Map<String, String>,
        body: ByteArray,
    ) {
        headers(out, status, reason, responseHeaders + ("Connection" to "close"))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun headers(out: BufferedOutputStream, status: Int, reason: String, values: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            values.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    private fun parseHeaders(text: String): Map<String, String> = buildMap {
        text.split("\r\n").drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                put(
                    line.substring(0, separator).trim().lowercase(Locale.ROOT),
                    line.substring(separator + 1).trim(),
                )
            }
        }
    }

    private fun descriptionXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
        <device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>WAM Bridge · M5</friendlyName><manufacturer>WAM Bridge</manufacturer>
        <modelName>WAM Bridge Mobile</modelName><modelNumber>0.1</modelNumber><UDN>uuid:${state.udn}</UDN>
        <iconList><icon><mimetype>image/png</mimetype><width>128</width><height>128</height><depth>32</depth><url>/icon.png</url></icon></iconList>
        <serviceList>
        ${serviceXml(AV_TRANSPORT, "AVTransport", "avtransport")}
        ${serviceXml(RENDERING_CONTROL, "RenderingControl", "renderingcontrol")}
        ${serviceXml(CONNECTION_MANAGER, "ConnectionManager", "connectionmanager")}
        </serviceList></device></root>
    """.trimIndent()

    private fun serviceXml(type: String, id: String, path: String): String =
        "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$id</serviceId>" +
            "<SCPDURL>/upnp/$path.xml</SCPDURL><controlURL>/upnp/control/$path</controlURL>" +
            "<eventSubURL>/upnp/event/$path</eventSubURL></service>"

    private fun avTransportScpd(): String = scpd(
        listOf(
            action("SetAVTransportURI", inArg("InstanceID", "InstanceID"), inArg("CurrentURI", "AVTransportURI"), inArg("CurrentURIMetaData", "AVTransportURIMetaData")),
            action("SetNextAVTransportURI", inArg("InstanceID", "InstanceID"), inArg("NextURI", "AVTransportURI"), inArg("NextURIMetaData", "AVTransportURIMetaData")),
            action("Play", inArg("InstanceID", "InstanceID"), inArg("Speed", "TransportPlaySpeed")),
            action("Pause", inArg("InstanceID", "InstanceID")),
            action("Stop", inArg("InstanceID", "InstanceID")),
            action("Seek", inArg("InstanceID", "InstanceID"), inArg("Unit", "A_ARG_TYPE_SeekMode"), inArg("Target", "A_ARG_TYPE_SeekTarget")),
            action("SetPlayMode", inArg("InstanceID", "InstanceID"), inArg("NewPlayMode", "CurrentPlayMode")),
            action("GetTransportInfo", inArg("InstanceID", "InstanceID"), outArg("CurrentTransportState", "TransportState"), outArg("CurrentTransportStatus", "TransportStatus"), outArg("CurrentSpeed", "TransportPlaySpeed")),
            action("GetPositionInfo", inArg("InstanceID", "InstanceID"), outArg("Track", "CurrentTrack"), outArg("TrackDuration", "CurrentTrackDuration"), outArg("TrackMetaData", "CurrentTrackMetaData"), outArg("TrackURI", "CurrentTrackURI"), outArg("RelTime", "RelativeTimePosition"), outArg("AbsTime", "AbsoluteTimePosition"), outArg("RelCount", "RelativeCounterPosition"), outArg("AbsCount", "AbsoluteCounterPosition")),
            action("GetMediaInfo", inArg("InstanceID", "InstanceID")),
            action("GetTransportSettings", inArg("InstanceID", "InstanceID")),
        ),
        listOf(
            variable("InstanceID", "ui4"), variable("AVTransportURI"), variable("AVTransportURIMetaData"),
            variable("TransportPlaySpeed"), variable("TransportState", events = true), variable("TransportStatus"),
            variable("CurrentTrack", "ui4"), variable("CurrentTrackDuration"), variable("CurrentTrackMetaData"), variable("CurrentTrackURI"),
            variable("RelativeTimePosition"), variable("AbsoluteTimePosition"), variable("RelativeCounterPosition", "i4"), variable("AbsoluteCounterPosition", "i4"),
            variable("A_ARG_TYPE_SeekMode"), variable("A_ARG_TYPE_SeekTarget"), variable("CurrentPlayMode"),
        ),
    )

    private fun renderingControlScpd(): String = scpd(
        listOf(
            action("SetVolume", inArg("InstanceID", "InstanceID"), inArg("Channel", "Channel"), inArg("DesiredVolume", "Volume")),
            action("GetVolume", inArg("InstanceID", "InstanceID"), inArg("Channel", "Channel"), outArg("CurrentVolume", "Volume")),
            action("SetMute", inArg("InstanceID", "InstanceID"), inArg("Channel", "Channel"), inArg("DesiredMute", "Mute")),
            action("GetMute", inArg("InstanceID", "InstanceID"), inArg("Channel", "Channel"), outArg("CurrentMute", "Mute")),
            action("ListPresets", inArg("InstanceID", "InstanceID")),
            action("SelectPreset", inArg("InstanceID", "InstanceID"), inArg("PresetName", "PresetName")),
        ),
        listOf(variable("InstanceID", "ui4"), variable("Channel"), variable("Volume", "ui2", true), variable("Mute", "boolean", true), variable("PresetName")),
    )

    private fun connectionManagerScpd(): String = scpd(
        listOf(
            action("GetProtocolInfo", outArg("Source", "SourceProtocolInfo"), outArg("Sink", "SinkProtocolInfo")),
            action("GetCurrentConnectionIDs", outArg("ConnectionIDs", "CurrentConnectionIDs")),
            action("GetCurrentConnectionInfo", inArg("ConnectionID", "A_ARG_TYPE_ConnectionID")),
        ),
        listOf(variable("SourceProtocolInfo"), variable("SinkProtocolInfo"), variable("CurrentConnectionIDs"), variable("A_ARG_TYPE_ConnectionID", "i4")),
    )

    private fun scpd(actions: List<String>, variables: List<String>): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>${actions.joinToString("")}</actionList>" +
            "<serviceStateTable>${variables.joinToString("")}</serviceStateTable></scpd>"

    private fun action(name: String, vararg args: String): String =
        "<action><name>$name</name>${if (args.isEmpty()) "" else "<argumentList>${args.joinToString("")}</argumentList>"}</action>"

    private fun inArg(name: String, related: String) = arg(name, "in", related)
    private fun outArg(name: String, related: String) = arg(name, "out", related)
    private fun arg(name: String, direction: String, related: String) =
        "<argument><name>$name</name><direction>$direction</direction><relatedStateVariable>$related</relatedStateVariable></argument>"

    private fun variable(name: String, type: String = "string", events: Boolean = false) =
        "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType></stateVariable>"

    override fun close() {
        if (!running.getAndSet(false)) return
        // Withdraw the renderer before tearing down port 1900. Without this,
        // control points can keep a dead instance cached for up to 30 minutes.
        sendByebye()
        closeResources()
    }

    private fun closeResources() {
        try { ssdpSocket?.close() } catch (_: Exception) { }
        try { httpServer?.close() } catch (_: Exception) { }
        ssdpSocket = null
        httpServer = null
        activeStream.getAndSet(null)?.let { socket ->
            streamSources.remove(socket)?.disconnect()
            try { socket.close() } catch (_: Exception) { }
        }
        streamSources.values.forEach { it.disconnect() }
        streamSources.clear()
        clientSockets.forEach { socket -> try { socket.close() } catch (_: Exception) { } }
        clientSockets.clear()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        executor.shutdownNow()
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    companion object {
        private const val SSDP_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_HOST = "$SSDP_GROUP:$SSDP_PORT"
        private const val SERVER_HEADER = "Android/1.0 UPnP/1.1 WAMBridge/0.1"
        private val SSDP_ADDRESS: InetAddress = InetAddress.getByName(SSDP_GROUP)
        private const val MAX_HEADER = 64 * 1024
        private const val MAX_BODY = 1024 * 1024
        private const val CLIENT_TIMEOUT_MS = 15_000
        private const val SOURCE_CONNECT_TIMEOUT_MS = 5_000
        private const val SOURCE_READ_TIMEOUT_MS = 15_000
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
        private const val SINK_PROTOCOLS = "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/L16:*,http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*"
        private val SSDP_TARGETS = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            AV_TRANSPORT,
            RENDERING_CONTROL,
            CONNECTION_MANAGER,
        )
    }
}
