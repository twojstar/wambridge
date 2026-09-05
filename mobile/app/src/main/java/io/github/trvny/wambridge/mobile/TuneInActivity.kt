package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class TuneInActivity : Activity() {
    private var widgetStopInProgress = false
    private lateinit var statusView: TextView
    private lateinit var refreshButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var muteButton: Button
    private lateinit var volumeDownButton: Button
    private lateinit var volumeUpButton: Button
    private lateinit var volumeView: TextView
    private lateinit var presetsView: LinearLayout

    private val artworkExecutor = Executors.newFixedThreadPool(3)
    private val artworkCache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val preferences by lazy {
        getSharedPreferences(RendererService.PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)

        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "TuneIn",
                "Native radio living on the M5, with the controls that Samsung forgot to keep alive.",
            ),
        )
        content.addView(buildControls())

        statusView = MobileUi.status(this)
        content.addView(statusView)
        content.addView(MobileUi.sectionTitle(this, "Presets"))

        presetsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(presetsView)

        setContentView(ScrollView(this).apply { addView(content) })
        val widgetStop = intent.action == ACTION_STOP_PLAYBACK
        if (widgetStop) intent.action = null
        when {
            savedInstanceState?.getBoolean(STATE_WIDGET_STOP) == true -> finish()
            widgetStop && RadioService.active -> {
                widgetStopInProgress = true
                startService(Intent(this, RadioService::class.java).apply { action = RadioService.ACTION_STOP })
                finish()
            }
            widgetStop -> {
                widgetStopInProgress = true
                stopPlayback(finishAfter = true)
            }
            else -> loadPresets()
        }
    }

    private fun buildControls(): View {
        val card = MobileUi.card(this)
        card.addView(MobileUi.label(this, "Playback"))
        card.addView(MobileUi.row(this).also { row ->
            refreshButton = MobileUi.button(this, "Refresh") { loadPresets() }
            playPauseButton = MobileUi.button(this, "Play / pause", MobileUi.ButtonKind.PRIMARY) { togglePlayback() }
            stopButton = MobileUi.button(this, "Stop", MobileUi.ButtonKind.DANGER) { stopPlayback() }
            MobileUi.addWeighted(row, refreshButton)
            MobileUi.addWeighted(row, playPauseButton)
            MobileUi.addWeighted(row, stopButton, marginDp = 0)
        })
        card.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@TuneInActivity, 10), 0, 0)
            volumeDownButton = MobileUi.button(this@TuneInActivity, "−") { changeVolume(-1) }
            volumeUpButton = MobileUi.button(this@TuneInActivity, "+") { changeVolume(+1) }
            muteButton = MobileUi.button(this@TuneInActivity, "Mute") { toggleMute() }
            volumeView = TextView(this@TuneInActivity).apply {
                text = "Volume"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.wam_text))
            }
            MobileUi.addWeighted(this, volumeDownButton, 0.7f)
            MobileUi.addWeighted(this, volumeView, 1.35f)
            MobileUi.addWeighted(this, volumeUpButton, 0.7f)
            MobileUi.addWeighted(this, muteButton, 1.1f, marginDp = 0)
        })
        return card
    }

    private fun resolveSpeaker(verifySaved: Boolean = true): String =
        SpeakerTarget.resolve(applicationContext, verifySaved)
            ?: throw IOException("No WAM speaker found on Wi-Fi")

    private fun loadPresets() {
        setButtonsEnabled(false)
        presetsView.removeAllViews()
        statusView.text = "Finding M5 and reading TuneIn presets…"

        Thread({
            val result = runCatching {
                releasePlaybackOwners()
                val target = resolveSpeaker()
                target to SamsungTuneIn.getPresets(applicationContext, target)
            }
            runOnUiThread {
                setButtonsEnabled(true)
                result.fold(
                    onSuccess = { (_, presets) -> showPresets(presets) },
                    onFailure = { error ->
                        statusView.text = "Could not read TuneIn: ${error.message ?: error.javaClass.simpleName}"
                    },
                )
            }
        }, "wam-mobile-tunein-list").start()
    }

    private fun showPresets(presets: List<SamsungTuneIn.Preset>) {
        presetsView.removeAllViews()
        if (presets.isEmpty()) {
            statusView.text = "The speaker returned no TuneIn presets."
            return
        }
        statusView.text = "${presets.size} preset${if (presets.size == 1) "" else "s"} ready."
        presets.forEach { preset -> presetsView.addView(presetCard(preset)) }
    }

    private fun presetCard(preset: SamsungTuneIn.Preset): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground()
            setOnClickListener { playPreset(preset) }
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }

        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.mipmap.ic_launcher)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(14, 0, 0, 0))
            }
            clipToOutline = true
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(12) })
        loadArtwork(logo, preset.thumbnail)

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = preset.title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        val detail = listOfNotNull(
            preset.description?.takeIf { it.isNotBlank() },
            preset.mediaId?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (detail.isNotBlank()) {
            copy.addView(TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(Color.DKGRAY)
                maxLines = 2
            })
        }
        row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MobileUi.button(this, "Play", MobileUi.ButtonKind.PRIMARY) { playPreset(preset) })
        return row
    }

    private fun playPreset(preset: SamsungTuneIn.Preset) {
        setButtonsEnabled(false)
        statusView.text = "Starting ${preset.title}…"

        Thread({
            val result = runCatching {
                releasePlaybackOwners()
                val target = resolveSpeaker()
                SamsungTuneIn.playSafely(applicationContext, target, preset)
            }
            runOnUiThread {
                setButtonsEnabled(true)
                result.fold(
                    onSuccess = {
                        statusView.text = "Playing · ${preset.title}"
                        volumeView.text = "Volume 3/30"
                        playPauseButton.text = "Pause"
                    },
                    onFailure = { error ->
                        statusView.text = "TuneIn start failed; speaker kept muted: ${error.message ?: error.javaClass.simpleName}"
                    },
                )
            }
        }, "wam-mobile-tunein-play").start()
    }

    private fun togglePlayback() {
        runSpeakerControl(
            progress = "Toggling playback…",
            action = { target ->
                when (SpeakerRemote.toggleNativePlayback(applicationContext, target)) {
                    SpeakerRemote.PlaybackToggleResult.PAUSED -> "Paused"
                    SpeakerRemote.PlaybackToggleResult.PLAYING -> "Playing"
                    SpeakerRemote.PlaybackToggleResult.NO_NATIVE_PLAYBACK ->
                        "No native TuneIn playback to control"
                }
            },
            onSuccess = { message ->
                if (message == "Paused") playPauseButton.text = "Play"
                if (message == "Playing") playPauseButton.text = "Pause"
            },
        )
    }

    private fun toggleMute() {
        runSpeakerControl(
            progress = "Toggling mute…",
            action = { target ->
                if (SpeakerRemote.toggleMute(applicationContext, target)) "Muted" else "Unmuted"
            },
            onSuccess = { message ->
                muteButton.text = if (message == "Muted") "Unmute" else "Mute"
            },
        )
    }

    private fun changeVolume(delta: Int) {
        runSpeakerControl(
            progress = "Changing volume…",
            action = { target ->
                val value = SpeakerRemote.changeVolume(applicationContext, target, delta)
                "Volume $value/30"
            },
            onSuccess = { message -> volumeView.text = message },
        )
    }

    private fun runSpeakerControl(
        progress: String,
        action: (String) -> String,
        onSuccess: (String) -> Unit = {},
    ) {
        setButtonsEnabled(false)
        statusView.text = progress
        Thread({
            val result = runCatching {
                val target = resolveSpeaker(verifySaved = false)
                action(target)
            }
            runOnUiThread {
                setButtonsEnabled(true)
                result.fold(
                    onSuccess = { message ->
                        onSuccess(message)
                        statusView.text = message
                    },
                    onFailure = { error ->
                        statusView.text = error.message ?: error.javaClass.simpleName
                    },
                )
            }
        }, "wam-mobile-tunein-control").start()
    }

    private fun stopPlayback(finishAfter: Boolean = false) {
        setButtonsEnabled(false)
        statusView.text = "Stopping TuneIn…"

        Thread({
            val result = runCatching {
                releasePlaybackOwners()
                val target = resolveSpeaker(verifySaved = false)
                endSpeakerPlayback(target)
            }
            runOnUiThread {
                setButtonsEnabled(true)
                result.fold(
                    onSuccess = { report ->
                        statusView.text = report
                        playPauseButton.text = "Play / pause"
                    },
                    onFailure = { error ->
                        statusView.text = "Could not stop playback: ${error.message ?: error.javaClass.simpleName}"
                    },
                )
                if (finishAfter) finish()
            }
        }, "wam-mobile-tunein-stop").start()
    }

    private fun loadArtwork(view: ImageView, url: String?) {
        val key = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        view.tag = key
        synchronized(artworkCache) { artworkCache.get(key) }?.let {
            view.setImageBitmap(it)
            return
        }
        artworkExecutor.execute {
            val bitmap = runCatching { downloadArtwork(key) }.getOrNull() ?: return@execute
            synchronized(artworkCache) { artworkCache.put(key, bitmap) }
            runOnUiThread {
                if (!isFinishing && view.tag == key) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun downloadArtwork(address: String): Bitmap {
        var lastError: Exception? = null
        for (connection in WifiLan.openHttpConnections(applicationContext, URL(address))) {
            connection.apply {
                connectTimeout = ARTWORK_TIMEOUT_MS
                readTimeout = ARTWORK_TIMEOUT_MS
                useCaches = true
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Artwork HTTP ${connection.responseCode}")
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > MAX_ARTWORK_BYTES) throw IOException("Artwork too large")
                        out.write(buffer, 0, count)
                    }
                }
                val bytes = out.toByteArray()
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Unsupported artwork image")
            } catch (error: Exception) {
                lastError = error
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("No active Wi-Fi network")
    }

    private fun roundedBackground(): GradientDrawable = MobileUi.rounded(
        this,
        fill = getColor(R.color.wam_surface),
        stroke = getColor(R.color.wam_border),
        radiusDp = 18,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun endSpeakerPlayback(target: String): String {
        val clientUuid = preferences.getString(KEY_CLIENT_UUID, null)
            ?: SamsungWamChannel.newClientUuid().also {
                preferences.edit().putString(KEY_CLIENT_UUID, it).apply()
            }
        val channel = SamsungWamChannel(applicationContext, target, clientUuid)
        val confirmDeadline = try {
            channel.connect()
            // Not a redundant pair, so do not collapse it into one call: SetFunc aimed at
            // wifi while the speaker is already on wifi does nothing at all - it is told to
            // become what it already is. Ending a preset takes the detour through another
            // source, which also lands back in submode=dlna, the idle state the rest of this
            // app expects. Measured on the M5 on 2026-08-19, where SetPlaybackControl stop
            // was refused on both CPM ("Current track token is empty.") and UIC (result="ng")
            // and, on a live stream, pause answered without error and changed nothing.
            //
            // aux rather than bt, and that is the whole point of the choice: the speaker
            // announces "Bluetooth is ready" out loud every time it is switched to bt, which
            // made a stop button talk. aux and soundshare both clear cp exactly as well and
            // say nothing (measured 2026-08-20). Do not "simplify" this back to bt.
            channel.selectFunction("aux")
            Thread.sleep(FUNCTION_SWITCH_PAUSE_MS)
            channel.selectFunction("wifi")
            val deadline = SystemClock.elapsedRealtime() + STOP_CONFIRM_TIMEOUT_MS
            // Sends are fire-and-forget here, so let the last one leave before the socket
            // closes. Waiting for the speaker to settle is the poll's job below.
            Thread.sleep(FUNCTION_SWITCH_PAUSE_MS)
            deadline
        } finally {
            channel.close()
        }

        // A write that left the phone is not a stop. Neither SetFunc is acknowledged to
        // the caller on this channel, so the state is read back and only what GetFunc
        // actually answered gets reported.
        //
        // Read it repeatedly, not once: the M5 leaves cp between two and three seconds
        // after SetFunc wifi (measured 2026-08-20), so a single read at a fixed delay
        // lands on submode=cp and calls a stop that worked unconfirmed.
        var lastRead: SamsungWamChannel.FunctionState? = null
        var lastError: Throwable? = null
        var stopped = false
        while (true) {
            val attempt = runCatching { SamsungWamChannel.readFunction(applicationContext, target) }
            val read = attempt.getOrNull()
            if (read == null) {
                // A read that failed is not a verdict; keep asking until the deadline.
                lastError = attempt.exceptionOrNull()
            } else {
                lastRead = read
                stopped = read.function.equals("wifi", ignoreCase = true) &&
                    !read.submode.isNullOrBlank() &&
                    !read.submode.equals("cp", ignoreCase = true)
            }
            if (stopped) break
            val remaining = confirmDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            Thread.sleep(minOf(STOP_POLL_INTERVAL_MS, remaining))
        }

        val finalRead = lastRead
        val reading = if (finalRead != null) {
            "function=${finalRead.function.orEmpty().ifBlank { "?" }} · " +
                "submode=${finalRead.submode.orEmpty().ifBlank { "(empty)" }}"
        } else {
            val error = lastError
            "GetFunc did not answer: ${error?.message ?: error?.javaClass?.simpleName ?: "no reply"}"
        }
        return if (stopped) {
            "Playback stopped · $reading"
        } else {
            "SetFunc aux/wifi sent, but the stop could not be confirmed · $reading"
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        MobileUi.setEnabled(refreshButton, enabled)
        MobileUi.setEnabled(playPauseButton, enabled)
        MobileUi.setEnabled(stopButton, enabled)
        MobileUi.setEnabled(muteButton, enabled)
        MobileUi.setEnabled(volumeDownButton, enabled)
        MobileUi.setEnabled(volumeUpButton, enabled)
        for (index in 0 until presetsView.childCount) {
            setViewTreeEnabled(presetsView.getChildAt(index), enabled)
        }
    }

    private fun setViewTreeEnabled(view: View, enabled: Boolean) {
        MobileUi.setEnabled(view, enabled)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setViewTreeEnabled(view.getChildAt(index), enabled)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (widgetStopInProgress) outState.putBoolean(STATE_WIDGET_STOP, true)
    }

    override fun onDestroy() {
        artworkExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun releasePlaybackOwners() {
        releaseRenderer()
        releaseRadio()
    }

    private fun releaseRenderer() {
        if (!RendererService.busy) return
        startService(
            Intent(this, RendererService::class.java).apply {
                action = RendererService.ACTION_STOP
            },
        )
        val deadline = SystemClock.elapsedRealtime() + OWNER_STOP_TIMEOUT_MS
        while (RendererService.busy && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        check(!RendererService.busy) { "Renderer did not release the WAM control channel" }
    }

    private fun releaseRadio() {
        if (!RadioService.active) return
        startService(
            Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_STOP
            },
        )
        val deadline = SystemClock.elapsedRealtime() + OWNER_STOP_TIMEOUT_MS
        while (RadioService.active && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        check(!RadioService.active) { "Radio did not release the WAM control channel" }
    }

    companion object {
        const val ACTION_STOP_PLAYBACK = "trvny.wambridge.mobile.TUNEIN_STOP_PLAYBACK"
        private const val STATE_WIDGET_STOP = "widget_stop_in_progress"
        private const val OWNER_STOP_TIMEOUT_MS = 2_500L
        private const val ARTWORK_TIMEOUT_MS = 5_000
        private const val MAX_ARTWORK_BYTES = 1024 * 1024

        /** Gap between `SetFunc aux` and `SetFunc wifi`, and the flush after the last send. */
        private const val FUNCTION_SWITCH_PAUSE_MS = 2_000L

        /** How long to keep asking `GetFunc` whether the speaker has left `cp`. */
        private const val STOP_CONFIRM_TIMEOUT_MS = 8_000L

        /** Gap between those reads - this firmware wedges under rapid calls. */
        private const val STOP_POLL_INTERVAL_MS = 1_000L

        private const val KEY_CLIENT_UUID = "tunein_client_uuid"
    }
}
