package io.github.trvny.wambridge.mobile

import android.content.Context

internal object SpeakerTarget {
    private const val KEY_SPEAKER_DEVICE_ID = "speaker_device_id"
    private const val IDENTITY_TIMEOUT_MS = 1_500
    private const val BOUND_RESOLVE_ATTEMPTS = 3
    private val resolutionLock = Any()

    data class Resolution(val ip: String, val deviceId: String?)
    data class BoundResolution(val resolution: Resolution, val wifi: WifiLan.Target) {
        val ip: String get() = resolution.ip
    }

    fun resolve(
        context: Context,
        verifySaved: Boolean = true,
        shouldContinue: () -> Boolean = { true },
    ): String? = withDiscoveryLock {
        val result = resolveLocked(context, verifySaved, shouldContinue) ?: return@withDiscoveryLock null
        if (!shouldContinue()) return@withDiscoveryLock null
        rememberResolved(context.applicationContext, result)
        result.ip
    }

    fun resolveBound(
        context: Context,
        verifySaved: Boolean = true,
        shouldContinue: () -> Boolean = { true },
    ): BoundResolution? = withDiscoveryLock {
        repeat(BOUND_RESOLVE_ATTEMPTS) {
            if (!shouldContinue()) return@withDiscoveryLock null
            val wifi = WifiLan.preferredTarget(context) ?: return@withDiscoveryLock null
            val result = resolveLocked(context, verifySaved, shouldContinue)
            if (!shouldContinue()) return@withDiscoveryLock null
            if (!WifiLan.isCurrentPreferred(context, wifi)) return@repeat
            if (result == null) return@withDiscoveryLock null

            val currentId = runCatching {
                SamsungWamChannel.readDeviceId(
                    context,
                    result.ip,
                    IDENTITY_TIMEOUT_MS,
                    wifi,
                )
            }.getOrNull()
            if (!WifiLan.isCurrentPreferred(context, wifi)) return@repeat
            val verified = if (result.deviceId != null) {
                currentId == result.deviceId
            } else {
                currentId != null || SamsungWamChannel.probe(context, result.ip, IDENTITY_TIMEOUT_MS, wifi)
            }
            if (!verified) return@withDiscoveryLock null
            val stable = Resolution(result.ip, currentId ?: result.deviceId)
            rememberResolved(context, stable)
            return@withDiscoveryLock BoundResolution(stable, wifi)
        }
        null
    }

    fun resolveUnpersisted(
        context: Context,
        verifySaved: Boolean = true,
        shouldContinue: () -> Boolean = { true },
    ): Resolution? = withDiscoveryLock {
        resolveLocked(context, verifySaved, shouldContinue)
    }

    private fun resolveLocked(
        context: Context,
        verifySaved: Boolean,
        shouldContinue: () -> Boolean,
    ): Resolution? {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
        val savedIp = preferences.getString(RendererService.KEY_SPEAKER_IP, "").orEmpty().trim()
        val savedId = preferences.getString(KEY_SPEAKER_DEVICE_ID, "").orEmpty().trim()
        val savedIsValid = RendererService.isReasonableIpv4(savedIp)

        if (!shouldContinue()) return null
        if (savedIsValid && (RadioService.running || !verifySaved)) {
            return Resolution(savedIp, savedId.ifBlank { null })
        }
        if (savedIsValid) {
            resolveSaved(appContext, savedIp, savedId, shouldContinue)?.let { return it }
        }
        if (!shouldContinue()) return null
        return discoverTarget(appContext, savedIp, savedId, shouldContinue)
    }

    private fun resolveSaved(
        context: Context,
        savedIp: String,
        savedId: String,
        shouldContinue: () -> Boolean,
    ): Resolution? {
        val currentId = identify(context, savedIp)
        if (!shouldContinue()) return null
        if (currentId != null && (savedId.isBlank() || currentId == savedId)) {
            return Resolution(savedIp, currentId)
        }
        if (savedId.isBlank() && currentId == null &&
            SamsungWamChannel.probe(context, savedIp, IDENTITY_TIMEOUT_MS)
        ) {
            if (!shouldContinue()) return null
            return Resolution(savedIp, null)
        }
        return null
    }

    private fun discoverTarget(
        context: Context,
        savedIp: String,
        savedId: String,
        shouldContinue: () -> Boolean,
    ): Resolution? {
        val discovered = WamDiscovery.discover(
            context,
            allowScan = true,
            shouldContinue = shouldContinue,
        ).speakers
        if (!shouldContinue()) return null
        val selected = selectCandidate(savedIp, savedId, discovered) { ip ->
            if (!shouldContinue()) null else identify(context, ip)
        } ?: return null
        val selectedId = identify(context, selected.ip)
        if (!shouldContinue()) return null
        return Resolution(selected.ip, selectedId)
    }

    /** Keep discovery/probes off the legacy speaker control port one at a time. */
    internal fun <T> withDiscoveryLock(block: () -> T): T =
        synchronized(resolutionLock) { block() }

    /** Persist a validated automatic result together with its stable device id. */
    fun rememberResolved(context: Context, result: Resolution) {
        val editor = context.applicationContext
            .getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(RendererService.KEY_SPEAKER_IP, result.ip)
        if (!result.deviceId.isNullOrBlank()) editor.putString(KEY_SPEAKER_DEVICE_ID, result.deviceId)
        editor.apply()
    }

    fun rememberManualIp(context: Context, ip: String) {
        val preferences = context.applicationContext
            .getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
        val previous = preferences.getString(RendererService.KEY_SPEAKER_IP, "").orEmpty().trim()
        val editor = preferences.edit().putString(RendererService.KEY_SPEAKER_IP, ip)
        if (previous != ip) editor.remove(KEY_SPEAKER_DEVICE_ID)
        editor.apply()
    }

    private fun remember(context: Context, ip: String, deviceId: String?) {
        val editor = context.getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(RendererService.KEY_SPEAKER_IP, ip)
        if (!deviceId.isNullOrBlank()) editor.putString(KEY_SPEAKER_DEVICE_ID, deviceId)
        editor.apply()
    }

    private fun identify(context: Context, ip: String): String? = runCatching {
        SamsungWamChannel.readDeviceId(context, ip, IDENTITY_TIMEOUT_MS)
    }.getOrNull()

    internal fun selectCandidate(
        savedIp: String,
        savedId: String,
        speakers: List<WamDiscovery.Speaker>,
        identify: (String) -> String?,
    ): WamDiscovery.Speaker? {
        if (savedId.isNotBlank()) {
            return speakers.firstOrNull { identify(it.ip) == savedId }
        }
        if (speakers.size == 1) return speakers.single()
        return speakers.firstOrNull { it.ip == savedIp }
    }
}
