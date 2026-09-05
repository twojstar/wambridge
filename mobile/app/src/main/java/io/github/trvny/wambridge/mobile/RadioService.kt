package io.github.trvny.wambridge.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RadioService : Service(), RadioProxyServer.Listener, SamsungWamChannel.Listener {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
    }
    private val startPending = AtomicBoolean(false)

    private var proxy: RadioProxyServer? = null
    private var channel: SamsungWamChannel? = null
    @Volatile private var volumeChannel: SamsungWamChannel? = null
    private var station: MobileRadioStation? = null
    private var safeVolumeApplied = false
    private var targetVolume = SAFE_START_VOLUME
    private var muted = false
    private var speakerIp = ""
    private var wifiWatcher: AutoCloseable? = null

    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiWatcher = runCatching { WifiLan.watch(this, ::onWifiChanged) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                execute {
                    stopRadio()
                    starting = false
                    lastStatus = "Stopped"
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PAUSE -> {
                execute { togglePause() }
                return START_NOT_STICKY
            }

            ACTION_MUTE -> {
                execute { toggleMute() }
                return START_NOT_STICKY
            }

            ACTION_VOLUME_DOWN -> {
                execute { changeVolume(-1) }
                return START_NOT_STICKY
            }

            ACTION_VOLUME_UP -> {
                execute { changeVolume(1) }
                return START_NOT_STICKY
            }

            ACTION_PLAY -> {
                val alias = intent.getStringExtra(EXTRA_ALIAS).orEmpty().trim()
                val tuneInId = intent.getStringExtra(EXTRA_TUNEIN_ID)
                promoteToForeground("Starting radio…")
                if (alias.isBlank() && tuneInId.isNullOrBlank()) {
                    lastStatus = "Choose a radio station first."
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (startPending.compareAndSet(false, true)) {
                    starting = true
                    WamBridgeWidget.updateAll(applicationContext)
                    execute {
                        try {
                            startStation(alias, tuneInId)
                        } finally {
                            startPending.set(false)
                            starting = false
                            WamBridgeWidget.updateAll(applicationContext)
                        }
                    }
                }
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        startPending.set(false)
        starting = false
        runCatching { wifiWatcher?.close() }
        wifiWatcher = null
        try {
            worker.submit { stopRadio() }.get(TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Best effort during process teardown.
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startStation(alias: String, tuneInId: String? = null) {
        if (destroyed) return
        stopRadio(removeForeground = false)
        releaseRenderer()

        val preferences = getSharedPreferences(RendererService.PREFS, MODE_PRIVATE)
        speakerIp = SpeakerTarget.resolve(applicationContext) ?: run {
            fail("No WAM speaker found on Wi-Fi.")
            return
        }

        val selected = radioStationToPlay(alias, tuneInId, RadioStationStore(this).all())
        if (selected == null) {
            fail("Radio station '$alias' is no longer saved.")
            return
        }

        val clientUuid = preferences.getString(KEY_CLIENT_UUID, null)
            ?: SamsungWamChannel.newClientUuid().also {
                preferences.edit().putString(KEY_CLIENT_UUID, it).apply()
            }

        // Already on the radio worker thread, so this blocking resolution never
        // touches the main one. A saved TuneIn id is resolved now rather than
        // stored, because the answer changes whenever the broadcaster moves its
        // endpoint - the failure a hardcoded URL cannot survive. Failure here is
        // not fatal: the saved URLs come back unchanged.
        val sources = TuneInResolver.candidateUrls(this, selected)
        // A catalogue station carries no saved URLs, so an unresolvable TuneIn id
        // leaves nothing to relay. Saying so beats handing the proxy an empty list.
        if (sources.isEmpty()) {
            fail("TuneIn has no directly playable stream for ${selected.alias}.")
            return
        }

        var activeProxy: RadioProxyServer? = null
        var activeChannel: SamsungWamChannel? = null
        try {
            activeProxy = RadioProxyServer(this, speakerIp, sources, this).also { it.start() }
            activeChannel = SamsungWamChannel(this, speakerIp, clientUuid, this).also { it.connect() }
            volumeChannel = activeChannel

            // Same startup rule as renderer and desktop radio: keep old firmware
            // silent while switching into URL playback. The proxy callback lifts
            // to step 3 only after the M5 has actually requested audio.
            activeChannel.setVolumeRaw(0)
            activeChannel.setMute(true)
            safeVolumeApplied = false
            targetVolume = SAFE_START_VOLUME
            muted = false
            activeChannel.offerStream(activeProxy.url)

            station = selected
            proxy = activeProxy
            channel = activeChannel
            activeProxy = null
            activeChannel = null
            // Start state belongs to the command, not to delayed speaker/proxy callbacks.
            // A late StartPlaybackEvent or stream-open signal must not undo a user pause.
            paused = false
            running = true
            lastStatus = "Starting ${selected.alias}…"
            publish(lastStatus)
        } catch (error: Exception) {
            if (volumeChannel === activeChannel) volumeChannel = null
            runCatching { activeChannel?.setVolumeRaw(0) }
            runCatching { activeChannel?.setMute(true) }
            runCatching { activeChannel?.close() }
            runCatching { activeProxy?.close() }
            fail("Could not start ${selected.alias}: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun releaseRenderer() {
        if (!RendererService.busy) return
        startService(
            Intent(this, RendererService::class.java).apply {
                action = RendererService.ACTION_STOP
            },
        )
        val deadline = SystemClock.elapsedRealtime() + RENDERER_STOP_TIMEOUT_MS
        while (RendererService.busy && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        check(!RendererService.busy) { "Renderer did not release the WAM control channel" }
    }

    private fun onWifiChanged() = execute { reconcileWifiEndpoint() }

    private fun reconcileWifiEndpoint() {
        if (!running) return
        val activeProxy = proxy ?: return
        val endpoints = WifiLan.endpoints(this)
        val bound = WifiLan.Endpoint(activeProxy.networkHandle, activeProxy.localAddress.hostAddress.orEmpty())
        when (WifiLan.endpointChange(bound, endpoints)) {
            WifiLan.EndpointChange.STABLE,
            WifiLan.EndpointChange.UNBOUND,
            -> return
            WifiLan.EndpointChange.LOST -> stopForWifiLoss()
            WifiLan.EndpointChange.MOVED -> restartForWifiChange()
        }
    }

    private fun stopForWifiLoss() {
        lastStatus = "Wi-Fi unavailable · radio stopped"
        publish(lastStatus)
        stopRadio()
        stopSelf()
    }

    private fun restartForWifiChange() {
        val selected = station ?: return
        lastStatus = "Wi-Fi changed · reconnecting ${selected.alias}…"
        publish(lastStatus)
        startStation(selected.alias, selected.tuneInId)
    }

    override fun onStreamOpened(sourceUrl: String) = execute {
        if (destroyed || !running) return@execute
        val alias = station?.alias ?: "radio"
        if (!safeVolumeApplied) {
            val activeChannel = channel ?: return@execute
            activeChannel.setVolumeRaw(audibleVolume())
            activeChannel.setMute(false)
            safeVolumeApplied = true
        }
        lastStatus = when {
            paused -> "Paused $alias"
            muted -> "Muted $alias"
            else -> "Playing $alias"
        }
        publish(lastStatus)
    }

    override fun onStreamClosed() = execute {
        if (destroyed || !running) return@execute
        val alias = station?.alias ?: "Radio"
        lastStatus = "$alias stream ended"
        stopRadio()
        stopSelf()
    }

    override fun onProxyError(message: String) = execute {
        if (destroyed || !running) return@execute
        lastStatus = "Radio error: $message"
        stopRadio()
        stopSelf()
    }

    override fun onPlaybackStarted() = execute {
        if (destroyed || !running) return@execute
        val alias = station?.alias ?: "radio"
        lastStatus = when {
            paused -> "Paused $alias"
            muted -> "Muted $alias"
            else -> "Playing $alias · confirmed"
        }
        publish(lastStatus)
    }

    override fun onReportedError(method: String?, code: String) = execute {
        if (destroyed || !running) return@execute
        val suffix = method?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        lastStatus = "M5 error $code$suffix"
        publish(lastStatus)
    }

    override fun onVolumeChanged(source: Any, raw: Int) {
        // VolumeLevel has no request ID. Source identity rejects delayed replies from a
        // retired session while still accepting physical changes on the channel being started.
        if (destroyed || source !== volumeChannel || (!running && raw == 0)) return
        execute {
            // Re-check after serialization too: an old callback can be queued before a
            // station switch and otherwise run after the new channel becomes active.
            if (destroyed || !running || source !== volumeChannel) return@execute
            if (paused || muted) {
                if (raw > 0) {
                    targetVolume = raw
                    // Physical buttons and other clients may lift a silent session.
                    // Remember their intent, then immediately restore transport-safe silence.
                    channel?.setVolumeRaw(0)
                }
            } else {
                targetVolume = raw
            }
        }
    }

    private fun togglePause() {
        if (!running) return
        val activeChannel = channel ?: return
        val alias = station?.alias ?: "Radio"
        paused = !paused
        if (safeVolumeApplied) activeChannel.setVolumeRaw(audibleVolume())
        lastStatus = if (paused) "Paused $alias" else "Resuming $alias…"
        publish(lastStatus)
    }

    private fun toggleMute() {
        if (!running) return
        val activeChannel = channel ?: return
        muted = !muted
        if (safeVolumeApplied) activeChannel.setVolumeRaw(audibleVolume())
        lastStatus = "${station?.alias ?: "Radio"} · ${if (muted) "muted" else "unmuted"}"
        publish(lastStatus)
    }

    private fun changeVolume(delta: Int) {
        if (!running) return
        val activeChannel = channel ?: return
        targetVolume = (targetVolume + delta)
            .coerceIn(SamsungWamChannel.MIN_VOLUME_STEP, SamsungWamChannel.MAX_VOLUME_STEP)
        if (safeVolumeApplied) activeChannel.setVolumeRaw(audibleVolume())
        lastStatus = "${station?.alias ?: "Radio"} · volume $targetVolume/30"
        publish(lastStatus)
    }

    private fun audibleVolume(): Int = if (paused || muted) 0 else targetVolume

    private fun stopRadio(removeForeground: Boolean = true) {
        if (channel != null) {
            runCatching { channel?.pause() }
        }
        safeVolumeApplied = false
        targetVolume = SAFE_START_VOLUME
        muted = false
        paused = false
        volumeChannel = null
        runCatching { channel?.close() }
        channel = null
        runCatching { proxy?.close() }
        proxy = null
        station = null
        speakerIp = ""
        running = false
        WamBridgeWidget.updateAll(applicationContext)
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun fail(message: String) {
        lastStatus = message
        publish(message)
        stopRadio()
        stopSelf()
    }

    private fun execute(action: () -> Unit) {
        if (destroyed) return
        try {
            worker.execute {
                if (!destroyed) action()
            }
        } catch (_: RejectedExecutionException) {
            // Service teardown won the race.
        }
    }

    private fun promoteToForeground(message: String) {
        lastStatus = message
        startForeground(NOTIFICATION_ID, buildNotification(message))
    }

    private fun publish(message: String) {
        startForeground(NOTIFICATION_ID, buildNotification(message))
        WamBridgeWidget.updateAll(applicationContext)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WAM Bridge radio",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            31,
            Intent(this, RadioStationsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RadioService::class.java).apply { this.action = action },
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
            .setContentTitle("WAM Bridge · Radio")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (paused) "Resume" else "Pause",
                    action(32, ACTION_TOGGLE_PAUSE),
                ).build(),
            )
            .addAction(Notification.Action.Builder(null, "−", action(33, ACTION_VOLUME_DOWN)).build())
            .addAction(Notification.Action.Builder(null, "+", action(34, ACTION_VOLUME_UP)).build())
            .addAction(Notification.Action.Builder(null, "Mute", action(35, ACTION_MUTE)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(36, ACTION_STOP)).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 3, 4))
            .build()
    }

    companion object {
        const val ACTION_PLAY = "trvny.wambridge.mobile.RADIO_PLAY"
        const val ACTION_STOP = "trvny.wambridge.mobile.RADIO_STOP"
        const val ACTION_TOGGLE_PAUSE = "trvny.wambridge.mobile.RADIO_TOGGLE_PAUSE"
        const val ACTION_MUTE = "trvny.wambridge.mobile.RADIO_MUTE"
        const val ACTION_VOLUME_DOWN = "trvny.wambridge.mobile.RADIO_VOLUME_DOWN"
        const val ACTION_VOLUME_UP = "trvny.wambridge.mobile.RADIO_VOLUME_UP"
        const val EXTRA_ALIAS = "station_alias"

        /**
         * Play a station that is not saved, straight from its TuneIn id - what the
         * speaker's own catalogue hands out as `mediaid`. Set alongside
         * [EXTRA_ALIAS], which is then only the name to show.
         */
        const val EXTRA_TUNEIN_ID = "station_tunein_id"

        private const val KEY_CLIENT_UUID = "radio_client_uuid"
        private const val CHANNEL_ID = "wambridge-radio"
        private const val NOTIFICATION_ID = 5102
        private const val SAFE_START_VOLUME = 3
        private const val RENDERER_STOP_TIMEOUT_MS = 2_500L
        private const val TEARDOWN_TIMEOUT_MS = 1_500L
        private const val WORKER_THREAD_NAME = "wam-mobile-radio"

        @Volatile var starting = false
            private set
        @Volatile var running = false
            private set
        val active: Boolean
            get() = starting || running
        @Volatile var paused = false
            private set
        @Volatile var lastStatus = "Stopped"
            private set
    }
}
