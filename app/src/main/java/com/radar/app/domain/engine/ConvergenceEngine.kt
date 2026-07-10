package com.radar.app.domain.engine

import com.radar.app.data.model.ActiveSignal
import com.radar.app.data.model.BleMetadata
import com.radar.app.data.model.DeviceClass
import com.radar.app.data.model.DeviceInfo
import com.radar.app.data.model.MotionSample
import com.radar.app.data.model.NetworkMetadata
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalTrend
import com.radar.app.data.model.SignalType
import com.radar.app.data.model.TrackedDevice
import com.radar.app.data.model.WifiApMetadata
import com.radar.app.data.provider.SignalProvider
import com.radar.app.di.ApplicationScope
import com.radar.app.domain.motion.MotionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class ConvergenceEngine @Inject constructor(
    private val motionTracker: MotionTracker,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _devices = MutableStateFlow<List<TrackedDevice>>(emptyList())
    val devices: StateFlow<List<TrackedDevice>> = _devices

    private val beliefs = ConcurrentHashMap<String, DeviceBelief>()
    private val macToStableId = ConcurrentHashMap<String, String>()
    private val providers = ConcurrentHashMap<SignalType, SignalProvider>()
    private var activeProtocols = SignalType.values().toMutableSet()
    private var lastMotionSample: MotionSample = MotionSample.STATIONARY
    private val recentRssiByDevice = ConcurrentHashMap<String, ArrayDeque<Int>>()

    // ─── Public API ──────────────────────────────────────────────────────────

    fun addProvider(provider: SignalProvider) {
        providers[provider.type] = provider
        scope.launch {
            provider.observations.collect { obs ->
                if (obs.sourceType in activeProtocols) processObservation(obs)
            }
        }
    }

    fun removeProvider(type: SignalType) { providers.remove(type) }

    fun setActiveProtocols(types: Set<SignalType>) {
        activeProtocols = types.toMutableSet()
    }

    fun start() {
        scope.launch {
            motionTracker.samples.collect { lastMotionSample = it }
        }
        providers.values.forEach { it.start() }
        motionTracker.start()
        scope.launch {
            while (true) {
                delay(5_000)
                pruneStaleDevices()
                publishDevices()
            }
        }
    }

    fun stop() {
        providers.values.forEach { it.stop() }
        motionTracker.stop()
    }

    fun getDevice(stableId: String): TrackedDevice? = beliefs[stableId]?.toTrackedDevice()

    // ─── Core processing ─────────────────────────────────────────────────────

    private fun processObservation(obs: SignalObservation) {
        val stableId = resolveStableId(obs)
        val belief = beliefs.getOrPut(stableId) {
            DeviceBelief(stableId = stableId, firstSeenMs = obs.timestampMs)
        }

        updateRssiHistory(stableId, obs.rssi)
        val trend = computeTrend(stableId)
        val mergedInfo = mergeDeviceInfo(belief.info, obs)

        beliefs[stableId] = belief.copy(
            activeMac = obs.sourceId,
            lastSeenMs = obs.timestampMs,
            lastRssi = obs.rssi,
            proximityScore = updateProximityBelief(belief, obs, trend),
            bearingDeg = updateBearingBelief(belief, obs, trend),
            bearingConfidence = if (belief.bearingDeg != null) lastMotionSample.headingConfidence else 0f,
            overallConfidence = computeOverallConfidence(obs, trend),
            observations = (belief.observations + obs).takeLast(20),
            info = mergedInfo
        )

        publishDevices()
    }

    private fun resolveStableId(obs: SignalObservation): String {
        macToStableId[obs.sourceId]?.let { return it }

        val candidate = beliefs.values
            .filter { it.activeMac != obs.sourceId && (obs.timestampMs - it.lastSeenMs) in 0..60_000 }
            .maxByOrNull { computeRotationScore(it, obs) }

        if (candidate != null && computeRotationScore(candidate, obs) > ROTATION_SCORE_THRESHOLD) {
            macToStableId[obs.sourceId] = candidate.stableId
            beliefs[candidate.stableId] = candidate.copy(
                activeMac = obs.sourceId,
                macHistory = candidate.macHistory + obs.sourceId,
                macRotationCount = candidate.macRotationCount + 1
            )
            return candidate.stableId
        }

        val newId = UUID.randomUUID().toString()
        macToStableId[obs.sourceId] = newId
        return newId
    }

    private fun computeRotationScore(belief: DeviceBelief, obs: SignalObservation): Float {
        val timeSinceLoss = (obs.timestampMs - belief.lastSeenMs).coerceAtLeast(0)
        val temporalScore = 1f - (timeSinceLoss / 60_000f).coerceIn(0f, 1f)
        val rssiSimilarity = if (belief.lastRssi != null && obs.rssi != null)
            1f - (abs(belief.lastRssi - obs.rssi) / 20f).coerceIn(0f, 1f)
        else 0.5f
        return temporalScore * 0.4f + rssiSimilarity * 0.4f + belief.proximityScore * 0.2f
    }

    private fun updateProximityBelief(
        belief: DeviceBelief, obs: SignalObservation, trend: SignalTrend
    ): Float {
        val current = belief.proximityScore
        val motion = lastMotionSample
        val rssiDelta = if (belief.lastRssi != null && obs.rssi != null)
            abs(belief.lastRssi - obs.rssi).toFloat() else 0f

        if (rssiDelta < motion.motionGate && motion.isStationary)
            return (current * 0.99f + obs.normalizedSignal * 0.01f).coerceIn(0f, 1f)

        val delta = when {
            trend == SignalTrend.APPROACHING && !motion.isStationary -> CONVERGENCE_STEP
            trend == SignalTrend.RECEDING  && !motion.isStationary -> -CONVERGENCE_STEP
            trend == SignalTrend.APPROACHING -> CONVERGENCE_STEP * 0.5f
            trend == SignalTrend.RECEDING   -> -CONVERGENCE_STEP * 0.3f
            else -> 0f
        }
        return (current + delta)
            .coerceIn(current - MAX_PROXIMITY_CHANGE_PER_TICK, current + MAX_PROXIMITY_CHANGE_PER_TICK)
            .coerceIn(0f, 1f)
    }

    private fun updateBearingBelief(
        belief: DeviceBelief, obs: SignalObservation, trend: SignalTrend
    ): Float? {
        val motion = lastMotionSample
        if (motion.isStationary || motion.headingConfidence < 0.3f) return belief.bearingDeg
        return when (trend) {
            SignalTrend.APPROACHING ->
                angleLerp(belief.bearingDeg ?: motion.headingDeg, motion.headingDeg, motion.headingConfidence * 0.3f)
            SignalTrend.RECEDING -> {
                val behind = (motion.headingDeg + 180f) % 360f
                angleLerp(belief.bearingDeg ?: behind, behind, 0.2f)
            }
            else -> belief.bearingDeg
        }
    }

    private fun computeTrend(stableId: String): SignalTrend {
        val history = recentRssiByDevice[stableId] ?: return SignalTrend.UNKNOWN
        if (history.size < 3) return SignalTrend.UNKNOWN
        val delta = history.takeLast(3).average() - history.dropLast(3).takeLast(3).average()
        return when {
            delta > 3.0  -> SignalTrend.APPROACHING
            delta < -3.0 -> SignalTrend.RECEDING
            else         -> SignalTrend.STABLE
        }
    }

    private fun updateRssiHistory(stableId: String, rssi: Int?) {
        rssi ?: return
        val h = recentRssiByDevice.getOrPut(stableId) { ArrayDeque(10) }
        if (h.size >= 10) h.removeFirst()
        h.addLast(rssi)
    }

    private fun computeOverallConfidence(obs: SignalObservation, trend: SignalTrend) =
        (obs.confidence + if (trend != SignalTrend.UNKNOWN) 0.1f else 0f).coerceIn(0f, 1f)

    private fun mergeDeviceInfo(existing: DeviceInfo, obs: SignalObservation): DeviceInfo {
        val meta = obs.metadata
        val name = when (meta) {
            is BleMetadata    -> meta.deviceName ?: existing.displayName
            is WifiApMetadata -> meta.ssid ?: existing.displayName
            is NetworkMetadata -> meta.hostname ?: existing.displayName
        }
        return existing.copy(
            displayName = name ?: existing.displayName,
            ipAddress = (meta as? NetworkMetadata)?.ipAddress ?: existing.ipAddress,
            mdnsServices = (meta as? NetworkMetadata)?.mdnsServiceTypes ?: existing.mdnsServices,
            availableProtocols = existing.availableProtocols + obs.sourceType,
            inferredClass = inferDeviceClass(obs, existing)
        )
    }

    private fun inferDeviceClass(obs: SignalObservation, existing: DeviceInfo): DeviceClass {
        if (existing.inferredClass != DeviceClass.UNKNOWN) return existing.inferredClass
        return when (val meta = obs.metadata) {
            is WifiApMetadata -> DeviceClass.AP
            is BleMetadata -> when {
                meta.advertisingIntervalMs != null && meta.advertisingIntervalMs > 2000 -> DeviceClass.IOT
                meta.deviceName?.contains("watch",      ignoreCase = true) == true -> DeviceClass.ACCESSORY
                meta.deviceName?.contains("buds",       ignoreCase = true) == true -> DeviceClass.ACCESSORY
                meta.deviceName?.contains("headphone",  ignoreCase = true) == true -> DeviceClass.ACCESSORY
                else -> DeviceClass.PHONE
            }
            else -> DeviceClass.UNKNOWN
        }
    }

    private fun pruneStaleDevices() {
        val now = System.currentTimeMillis()
        beliefs.entries.removeIf { (_, b) -> now - b.lastSeenMs > 30_000L }
    }

    private fun publishDevices() {
        _devices.value = beliefs.values.map { it.toTrackedDevice() }.sortedByDescending { it.proximityScore }
    }

    private fun angleLerp(from: Float, to: Float, t: Float): Float {
        var diff = to - from
        while (diff >  180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return (from + diff * t + 360f) % 360f
    }

    companion object {
        private const val CONVERGENCE_STEP = 0.05f
        private const val MAX_PROXIMITY_CHANGE_PER_TICK = 0.15f
        private const val ROTATION_SCORE_THRESHOLD = 0.55f
    }
}

private data class DeviceBelief(
    val stableId: String,
    val activeMac: String = "",
    val macHistory: List<String> = emptyList(),
    val macRotationCount: Int = 0,
    val proximityScore: Float = 0f,
    val bearingDeg: Float? = null,
    val bearingConfidence: Float = 0f,
    val overallConfidence: Float = 0f,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val firstSeenMs: Long = System.currentTimeMillis(),
    val lastRssi: Int? = null,
    val info: DeviceInfo = DeviceInfo(),
    val observations: List<SignalObservation> = emptyList()
) {
    fun toTrackedDevice() = TrackedDevice(
        stableId = stableId,
        proximityScore = proximityScore,
        bearingDeg = bearingDeg,
        bearingConfidence = bearingConfidence,
        overallConfidence = overallConfidence,
        lastUpdatedMs = lastSeenMs,
        info = info.copy(knownMacs = macHistory.ifEmpty { listOf(activeMac) }, macRotationCount = macRotationCount),
        signals = observations.groupBy { it.sourceType }.map { (type, obs) ->
            ActiveSignal(type, obs.maxByOrNull { it.timestampMs }!!.rssi, SignalTrend.UNKNOWN)
        }
    )
}
