package io.github.trvny.wambridge.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class RendererService : Service(), RendererCallbacks, SamsungWamChannel.Listener {
    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING }

    private var renderer: UpnpRenderer? = null
    private var wamChannel: SamsungWamChannel? = null
    private var rendererState: RendererState? = null
    private var speakerIp: String = ""
    private var clientUuid: String = ""
    private var ownsPlayback = false
    private var safeVolumeApplied = false
    private val channelLock = Any()
    private val idleLock = Any()
    private var idleRelease: ScheduledFuture<*>? = null
    private var wifiWatcher: AutoCloseable? = null
    private val startPending = AtomicBoolean(false)
    private val desiredRunning = AtomicBoolean(false)
    private val commandGeneration = AtomicInteger(0)
    @Volatile private var latestStartId = 0
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
    }
    private val idleScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "wam-mobile-idle-release").apply { isDaemon = true }
    }

    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiWatcher = runCatching { WifiLan.watch(this, ::onWifiChanged) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                latestStartId = startId
                if (desiredRunning.getAndSet(false)) commandGeneration.incrementAndGet()
                if (phase != Phase.STOPPED) {
                    lastStatus = "Stopping renderer…"
                    setPhase(Phase.STOPPING)
                    publish(lastStatus)
                }
                worker.execute {
                    if (!desiredRunning.get()) {
                        stopRenderer()
                        if (!desiredRunning.get()) stopSelfResult(startId)
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                latestStartId = startId
                if (!desiredRunning.getAndSet(true)) commandGeneration.incrementAndGet()
                promoteToForeground("Starting...")
                if (running) {
                    publish(lastStatus)
                } else {
                    setPhase(Phase.STARTING)
                    enqueueStart()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        desiredRunning.set(false)
        commandGeneration.incrementAndGet()
        startPending.set(false)
        setPhase(Phase.STOPPING)
        cancelIdleRelease()
        runCatching { wifiWatcher?.close() }
        wifiWatcher = null

        try {
            worker.submit { stopRenderer() }.get(DESTROY_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Best effort. The worker is stopped below even if teardown times out.
        }

        idleScheduler.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enqueueStart() {
        if (destroyed || !desiredRunning.get() || running) return
        if (!startPending.compareAndSet(false, true)) return
        val generation = commandGeneration.get()
        val startId = latestStartId
        worker.execute {
            try {
                if (shouldKeepStarting(generation)) startRenderer(generation, startId)
            } finally {
                startPending.set(false)
                if (desiredRunning.get() && !running && !destroyed && WifiLan.addresses(this).isNotEmpty()) {
                    enqueueStart()
                } else if (!desiredRunning.get() && !running) {
                    stopSelfResult(startId)
                }
            }
        }
    }

    private fun startRenderer(generation: Int, startId: Int) {
        if (!shouldKeepStarting(generation)) return

        releaseRadio()
        if (!shouldKeepStarting(generation)) return

        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        lastStatus = "Finding WAM speaker on Wi-Fi…"
        publish(lastStatus)
        val target = SpeakerTarget.resolve(applicationContext) { shouldKeepStarting(generation) }
        if (!shouldKeepStarting(generation)) return
        if (target == null) {
            if (WifiLan.addresses(this).isEmpty()) {
                lastStatus = "Waiting for Wi-Fi…"
                publish(lastStatus)
                return
            }
            lastStatus = "No WAM speaker found on Wi-Fi."
            publish(lastStatus)
            failCurrentStart(generation, startId)
            return
        }

        if (renderer != null && speakerIp == target) {
            lastStatus = "Ready · ${renderer!!.localAddress.hostAddress}:${renderer!!.port} → $speakerIp · speaker released"
            setPhase(Phase.RUNNING)
            publish(lastStatus)
            return
        }
        stopRenderer(removeForeground = false, updatePhase = false)

        speakerIp = target
        clientUuid = preferences.getString(KEY_CLIENT_UUID, null)
            ?: SamsungWamChannel.newClientUuid().also {
                preferences.edit().putString(KEY_CLIENT_UUID, it).apply()
            }
        val rendererUdn = preferences.getString(KEY_RENDERER_UDN, null)
            ?: SamsungWamChannel.newClientUuid().also {
                preferences.edit().putString(KEY_RENDERER_UDN, it).apply()
            }

        var activeRenderer: UpnpRenderer? = null
        try {
            val state = RendererState(rendererUdn)
            activeRenderer = UpnpRenderer(this, state, this, target)
            activeRenderer.start()
            if (!shouldKeepStarting(generation)) return

            rendererState = state
            renderer = activeRenderer
            activeRenderer = null

            ownsPlayback = false
            safeVolumeApplied = false
            lastStatus = "Ready · ${renderer!!.localAddress.hostAddress}:${renderer!!.port} → $speakerIp · speaker released"
            setPhase(Phase.RUNNING)
            publish(lastStatus)
        } catch (error: Exception) {
            if (shouldKeepStarting(generation)) {
                lastStatus = "Could not start adapter: ${error.message ?: error.javaClass.simpleName}"
                failCurrentStart(generation, startId)
            }
        } finally {
            try {
                activeRenderer?.close()
            } catch (_: Exception) {
                // Best effort while abandoning a partially started renderer.
            }
        }
    }

    private fun onWifiChanged() {
        if (destroyed || !desiredRunning.get()) return
        try {
            worker.execute {
                if (!destroyed && desiredRunning.get()) reconcileWifiEndpoint()
            }
        } catch (_: RejectedExecutionException) {
            // Service teardown won the race.
        }
    }

    private fun reconcileWifiEndpoint() {
        val endpoints = WifiLan.endpoints(this)
        val activeRenderer = renderer
        val bound = activeRenderer?.localAddress?.hostAddress?.let {
            WifiLan.Endpoint(activeRenderer.networkHandle, it)
        }
        when (WifiLan.endpointChange(bound, endpoints)) {
            WifiLan.EndpointChange.STABLE -> return
            WifiLan.EndpointChange.UNBOUND -> {
                if (endpoints.isEmpty() || phase != Phase.STARTING) return
                lastStatus = "Wi-Fi ready · rebuilding renderer…"
                publish(lastStatus)
                enqueueStart()
            }
            WifiLan.EndpointChange.LOST -> waitForWifi()
            WifiLan.EndpointChange.MOVED -> rebuildForWifiChange()
        }
    }

    private fun waitForWifi() {
        stopRenderer(removeForeground = false, updatePhase = false)
        lastStatus = "Wi-Fi unavailable · waiting…"
        setPhase(Phase.STARTING)
        publish(lastStatus)
    }

    private fun rebuildForWifiChange() {
        stopRenderer(removeForeground = false, updatePhase = false)
        lastStatus = "Wi-Fi changed · rebuilding renderer…"
        setPhase(Phase.STARTING)
        publish(lastStatus)
        enqueueStart()
    }

    private fun shouldKeepStarting(generation: Int): Boolean =
        !destroyed && desiredRunning.get() && commandGeneration.get() == generation &&
            !Thread.currentThread().isInterrupted

    private fun failCurrentStart(generation: Int, startId: Int) {
        if (commandGeneration.get() == generation) desiredRunning.set(false)
        stopRenderer()
        stopSelfResult(startId)
    }

    private fun releaseRadio() {
        if (!RadioService.running) return
        startService(
            Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_STOP
            },
        )
        val deadline = SystemClock.elapsedRealtime() + RADIO_STOP_TIMEOUT_MS
        while (RadioService.running && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        check(!RadioService.running) { "Radio did not release the WAM control channel" }
    }

    private fun ensureChannel(): SamsungWamChannel = synchronized(channelLock) {
        wamChannel?.let { return it }
        check(speakerIp.isNotBlank()) { "Speaker is not configured" }
        check(clientUuid.isNotBlank()) { "Client UUID is not configured" }
        SamsungWamChannel(applicationContext, speakerIp, clientUuid, this).also {
            it.connect()
            wamChannel = it
        }
    }

    private fun closeWamChannel() {
        synchronized(channelLock) {
            try {
                wamChannel?.close()
            } catch (_: Exception) {
                // Best effort while releasing the speaker.
            }
            wamChannel = null
        }
    }

    private fun cancelIdleRelease() {
        synchronized(idleLock) {
            idleRelease?.cancel(false)
            idleRelease = null
        }
    }

    private fun scheduleIdleRelease() {
        if (destroyed) return
        synchronized(idleLock) {
            if (destroyed) return
            idleRelease?.cancel(false)
            idleRelease = idleScheduler.schedule({
                try {
                    worker.execute {
                        if (destroyed || !ownsPlayback) return@execute
                        try {
                            wamChannel?.pause()
                        } catch (_: Exception) {
                            // Closing the channel still prevents the adapter from holding resources.
                        } finally {
                            ownsPlayback = false
                            safeVolumeApplied = false
                            closeWamChannel()
                            rendererState?.transportState = "STOPPED"
                            publish("Stream ended · speaker released")
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // Service teardown won the race.
                }
            }, STREAM_RELEASE_GRACE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun stopRenderer(
        removeForeground: Boolean = true,
        updatePhase: Boolean = true,
    ) {
        if (updatePhase && phase != Phase.STOPPED) setPhase(Phase.STOPPING)
        cancelIdleRelease()
        rendererState?.transportState = "STOPPED"
        if (ownsPlayback) {
            try {
                wamChannel?.pause()
            } catch (_: Exception) {
                // Best effort: teardown must not keep the foreground service alive.
            }
        }
        ownsPlayback = false
        safeVolumeApplied = false
        closeWamChannel()
        try {
            renderer?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        renderer = null
        rendererState = null
        if (updatePhase) {
            lastStatus = "Stopped"
            setPhase(Phase.STOPPED)
        }
        speakerIp = ""
        clientUuid = ""
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun runOnWorker(action: () -> Unit) {
        if (destroyed) return
        if (Thread.currentThread().name == WORKER_THREAD_NAME) {
            action()
            return
        }

        try {
            worker.submit {
                if (!destroyed) action()
            }.get(CONTROL_ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            throw IllegalStateException("Adapter control action failed", error)
        }
    }

    private fun dispatchWamEvent(action: () -> Unit) {
        if (destroyed) return
        try {
            worker.execute {
                if (!destroyed) action()
            }
        } catch (_: RejectedExecutionException) {
            // Service teardown won the race.
        }
    }

    override fun onPlay(rendererStreamUrl: String) = runOnWorker {
        cancelIdleRelease()
        val channel = ensureChannel()

        // Old WAM firmware may jump volume while switching into URL playback.
        // Keep it silent through SetUrlPlayback and only lift to the bounded
        // start step after the speaker has actually requested the proxy stream.
        channel.setVolumeRaw(0)
        safeVolumeApplied = false
        ownsPlayback = true
        try {
            channel.offerStream(rendererStreamUrl)
        } catch (error: Exception) {
            ownsPlayback = false
            closeWamChannel()
            throw error
        }

        rendererState?.transportState = "TRANSITIONING"
        publish("Starting playback…")
    }

    override fun onStreamOpened() = runOnWorker {
        cancelIdleRelease()
        if (ownsPlayback && !safeVolumeApplied) {
            ensureChannel().setVolumeRaw(SAFE_START_VOLUME)
            safeVolumeApplied = true
        }
        if (ownsPlayback) {
            rendererState?.transportState = "PLAYING"
            publish("Streaming player → M5")
        }
    }

    override fun onStreamClosed() {
        if (destroyed) return
        runOnWorker {
            if (ownsPlayback) scheduleIdleRelease()
        }
    }

    override fun onPause() = runOnWorker {
        cancelIdleRelease()
        if (ownsPlayback) {
            try {
                wamChannel?.pause()
            } finally {
                ownsPlayback = false
                safeVolumeApplied = false
                closeWamChannel()
            }
        } else {
            closeWamChannel()
        }
        rendererState?.transportState = "PAUSED_PLAYBACK"
        publish("Paused · speaker released")
    }

    override fun onStop() = runOnWorker {
        cancelIdleRelease()
        if (ownsPlayback) {
            try {
                wamChannel?.pause()
            } finally {
                ownsPlayback = false
                safeVolumeApplied = false
                closeWamChannel()
            }
        } else {
            closeWamChannel()
        }
        rendererState?.transportState = "STOPPED"
        publish("Stopped · speaker released")
    }

    override fun onVolume(percent: Int) = runOnWorker {
        val normalized = percent.coerceIn(0, 100)
        val raw = (normalized * 30.0 / 100.0).roundToInt().coerceIn(0, 30)
        val channel = ensureChannel()
        channel.setVolumeRaw(raw)
        safeVolumeApplied = true
        rendererState?.volumePercent = normalized
        if (!ownsPlayback) closeWamChannel()
    }

    override fun onMute(muted: Boolean) = runOnWorker {
        val channel = ensureChannel()
        channel.setMute(muted)
        rendererState?.muted = muted
        if (!ownsPlayback) closeWamChannel()
    }

    override fun onPlaybackStarted() = dispatchWamEvent {
        if (!ownsPlayback) return@dispatchWamEvent
        rendererState?.transportState = "PLAYING"
        publish("Streaming player → M5 · confirmed")
    }

    override fun onReportedError(method: String?, code: String) = dispatchWamEvent {
        val source = method?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        rendererState?.lastError = "M5 error $code$source"
        publish("M5 reported error $code$source")
    }

    private fun promoteToForeground(message: String) {
        lastStatus = message
        startForeground(NOTIFICATION_ID, buildNotification(message))
    }

    private fun publish(message: String) {
        lastStatus = message
        startForeground(NOTIFICATION_ID, buildNotification(message))
    }

    /**
     * Tell the widget and the quick-settings tile that [running] has changed.
     * Both read the flag themselves, so they only need waking up; neither call
     * needs the main thread, and neither may take playback down if it fails.
     */
    private fun setPhase(value: Phase) {
        if (phase == value) return
        phase = value
        notifyRendererStateChanged()
    }

    private fun notifyRendererStateChanged() {
        val context = applicationContext
        try {
            WamBridgeWidget.updateAll(context)
        } catch (error: Exception) {
            Log.d(TAG, "Could not refresh the widget", error)
        }
        try {
            TileService.requestListeningState(
                context,
                ComponentName(context, WamBridgeTileService::class.java),
            )
        } catch (error: Exception) {
            Log.d(TAG, "Could not refresh the quick-settings tile", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mobile UPnP adapter status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, RendererService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
            .build()
    }

    companion object {
        const val ACTION_START = "trvny.wambridge.mobile.START"
        const val ACTION_STOP = "trvny.wambridge.mobile.STOP"
        const val PREFS = "mobile-adapter"
        const val KEY_SPEAKER_IP = "speaker_ip"
        private const val KEY_CLIENT_UUID = "wam_client_uuid"
        private const val KEY_RENDERER_UDN = "renderer_udn"
        private const val CHANNEL_ID = "wambridge-renderer"
        private const val NOTIFICATION_ID = 5101
        private const val SAFE_START_VOLUME = 3
        private const val STREAM_RELEASE_GRACE_SECONDS = 15L
        private const val DESTROY_RELEASE_TIMEOUT_MS = 1_500L
        private const val RADIO_STOP_TIMEOUT_MS = 2_500L
        private const val CONTROL_ACTION_TIMEOUT_MS = 5_000L
        private const val WORKER_THREAD_NAME = "wam-mobile-service"
        private const val TAG = "WamBridgeRenderer"

        @Volatile var phase: Phase = Phase.STOPPED
            private set
        val running: Boolean
            get() = phase == Phase.RUNNING
        val active: Boolean
            get() = phase == Phase.STARTING || phase == Phase.RUNNING
        val transitioning: Boolean
            get() = phase == Phase.STARTING || phase == Phase.STOPPING
        val busy: Boolean
            get() = phase != Phase.STOPPED
        @Volatile var lastStatus: String = "Stopped"
            private set

        fun isReasonableIpv4(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 4 && parts.all { part ->
                val number = part.toIntOrNull()
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    number != null && number in 0..255
            }
        }
    }
}
