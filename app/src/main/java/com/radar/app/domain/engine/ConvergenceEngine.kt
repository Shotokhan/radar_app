package com.radar.app.domain.engine

import com.radar.app.data.model.ActiveSignal
import com.radar.app.data.model.DeviceClass
import com.radar.app.data.model.DeviceInfo
import com.radar.app.data.model.MotionSample
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalTrend
import com.radar.app.data.model.SignalType
import com.radar.app.data.model.TrackedDevice
import com.radar.app.data.model.Vector2D
import com.radar.app.data.model.BleMetadata
import com.radar.app.data.model.WifiApMetadata
import com.radar.app.data.model.NetworkMetadata
import com.radar.app.data.provider.SignalProvider
import com.radar.app.domain.motion.MotionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val motionTracker: MotionTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _devices = MutableStateFlow<List<TrackedDevice>>(emptyList())
    val devices: StateFlow<List<TrackedDevice>> = _devices

    // Internal belief state per stableId
    private val beliefs = ConcurrentHashMap<String, DeviceBelief>()

    // Maps observed sourceId (MAC/BSSID) → stableId (our internal UUID)
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
                if (obs.sourceType in activeProtocols) {
                    processObservation(obs)
                }
            }
        }
    }

    fun removeProvider(type: SignalType) {
        providers.remove(type)
    }

    fun setActiveProtocols(types: Set<SignalType>) {
        activeProtocols = types.toMutableSet()
    }

    fun start() {
        scope.launch {
            motionTracker.samples.collect { sample ->
                lastMotionSample = sample
            }
        }
        providers.values.forEach { it.start() }
        motionTracker.start()

        // Periodic cleanup of stale devices
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                pruneStaleDevices()
                publishDevices()
            }
        }
    }

    fun stop() {
        providers.values.forEach { it.stop() }
        motionTracker.stop()
    }

    fun getDevice(stableId: String): TrackedDevice? {
        return beliefs[stableId]?.toTrackedDevice()
    }

    // ─── Core processing ─────────────────────────────────────────────────────

    private fun processObservation(obs: SignalObservation) {
        val stableId = resolveStableId(obs)
        val belief = beliefs.getOrPut(stableId) {
            DeviceBelief(stableId = stableId, firstSeenMs = obs.timestampMs)
        }

        updateRssiHistory(stableId, obs.rssi)
        val trend = computeTrend(stableId)
        val newProximity = updateProximityBelief(belief, obs, trend)
        val newBearing = updateBearingBelief(belief, obs, trend)

        val updatedBelief = belief.copy(
            activeMac = obs.sourceId,
            lastSeenMs = obs.timestampMs,
            lastRssi = obs.rssi,
            proximityScore = newProximity,
            bearingDeg = newBearing,
            bearingConfidence = if (newBearing != null) lastMotionSample.headingConfidence else 0f,
            overallConfidence = computeOverallConfidence(obs, trend),
            observations = (belief.observations + obs).takeLast(20)
        ).also { updated ->
            // Merge DeviceInfo from new observation
            val mergedInfo = mergeDeviceInfo(belief.info, obs)
            beliefs[stableId] = updated.copy(info = mergedInfo)
        }

        publishDevices()
    }

    /**
     * Resolves a sourceId (MAC/BSSID) to a stable internal ID.
     * Implements spatial-temporal MAC rotation detection.
     */
    private fun resolveStableId(obs: SignalObservation): String {
        // Already known MAC
        macToStableId[obs.sourceId]?.let { return it }

        // Try to match to a recently-lost device (MAC rotation detection)
        val rotationCandidate = beliefs.values
            .filter { belief ->
                val timeSinceLoss = obs.timestampMs - belief.lastSeenMs
                timeSinceLoss in 0..60_000 // within 60 seconds
            }
            .filter { belief ->
                // Only match if the old MAC is no longer actively seen
                belief.activeMac != obs.sourceId
            }
            .maxByOrNull { belief ->
                computeRotationScore(belief, obs)
            }

        if (rotationCandidate != null &&
            computeRotationScore(rotationCandidate, obs) > ROTATION_SCORE_THRESHOLD) {

            // This new MAC is likely the same device
            macToStableId[obs.sourceId] = rotationCandidate.stableId
            beliefs[rotationCandidate.stableId] = rotationCandidate.copy(
                activeMac = obs.sourceId,
                macHistory = rotationCandidate.macHistory + obs.sourceId,
                macRotationCount = rotationCandidate.macRotationCount + 1
            )
            return rotationCandidate.stableId
        }

        // New device
        val newStableId = UUID.randomUUID().toString()
        macToStableId[obs.sourceId] = newStableId
        return newStableId
    }

    /**
     * Scores how likely obs is a MAC rotation of an existing belief.
     * Combines spatial proximity + temporal proximity + RSSI similarity.
     */
    private fun computeRotationScore(belief: DeviceBelief, obs: SignalObservation): Float {
        val timeSinceLoss = (obs.timestampMs - belief.lastSeenMs).coerceAtLeast(0)
        val temporalScore = 1f - (timeSinceLoss / 60_000f).coerceIn(0f, 1f)

        val rssiSimilarity = if (belief.lastRssi != null && obs.rssi != null) {
            val delta = abs(belief.lastRssi - obs.rssi)
            1f - (delta / 20f).coerceIn(0f, 1f)
        } else 0.5f

        // Spatial score: high proximity score implies device is "nearby" — likely same spot
        val spatialScore = belief.proximityScore

        return (temporalScore * 0.4f) + (rssiSimilarity * 0.4f) + (spatialScore * 0.2f)
    }

    /**
     * Updates proximity score using motion-correlated RSSI gradient.
     * Key invariant: if user walks toward device, proximity increases on average.
     */
    private fun updateProximityBelief(
        belief: DeviceBelief,
        obs: SignalObservation,
        trend: SignalTrend
    ): Float {
        val current = belief.proximityScore
        val motion = lastMotionSample

        // Only update proximity if signal delta exceeds the motion gate
        val rssiDelta = if (belief.lastRssi != null && obs.rssi != null)
            abs(belief.lastRssi - obs.rssi).toFloat()
        else 0f

        if (rssiDelta < motion.motionGate && motion.isStationary) {
            // Signal noise without motion — dampen toward current value, don't move
            return current * 0.99f + obs.normalizedSignal * 0.01f
        }

        val convergenceDelta = when {
            trend == SignalTrend.APPROACHING && !motion.isStationary -> CONVERGENCE_STEP
            trend == SignalTrend.RECEDING && !motion.isStationary -> -CONVERGENCE_STEP
            trend == SignalTrend.APPROACHING && motion.isStationary -> CONVERGENCE_STEP * 0.5f
            trend == SignalTrend.RECEDING && motion.isStationary -> -CONVERGENCE_STEP * 0.3f
            else -> 0f
        }

        // Inertial smoothing: max change per tick is capped to prevent teleporting
        val rawNew = current + convergenceDelta
        val maxChange = MAX_PROXIMITY_CHANGE_PER_TICK
        val clamped = rawNew.coerceIn(current - maxChange, current + maxChange)

        return clamped.coerceIn(0f, 1f)
    }

    /**
     * Estimates bearing from motion heading + signal trend correlation.
     */
    private fun updateBearingBelief(
        belief: DeviceBelief,
        obs: SignalObservation,
        trend: SignalTrend
    ): Float? {
        val motion = lastMotionSample
        if (motion.isStationary || motion.headingConfidence < 0.3f) return belief.bearingDeg

        return when (trend) {
            SignalTrend.APPROACHING -> {
                // Device is roughly in the direction we're walking
                val existing = belief.bearingDeg
                if (existing == null) {
                    motion.headingDeg
                } else {
                    // Weighted average: pull bearing toward current heading
                    val weight = motion.headingConfidence * 0.3f
                    angleLerp(existing, motion.headingDeg, weight)
                }
            }
            SignalTrend.RECEDING -> {
                // Device is roughly behind us
                val behind = (motion.headingDeg + 180f) % 360f
                val existing = belief.bearingDeg ?: behind
                angleLerp(existing, behind, 0.2f)
            }
            else -> belief.bearingDeg
        }
    }

    private fun computeTrend(stableId: String): SignalTrend {
        val history = recentRssiByDevice[stableId] ?: return SignalTrend.UNKNOWN
        if (history.size < 3) return SignalTrend.UNKNOWN

        val recent = history.takeLast(3)
        val older = history.dropLast(3).takeLast(3)
        if (older.isEmpty()) return SignalTrend.UNKNOWN

        val recentAvg = recent.average()
        val olderAvg = older.average()
        val delta = recentAvg - olderAvg

        return when {
            delta > 3.0 -> SignalTrend.APPROACHING
            delta < -3.0 -> SignalTrend.RECEDING
            else -> SignalTrend.STABLE
        }
    }

    private fun updateRssiHistory(stableId: String, rssi: Int?) {
        rssi ?: return
        val history = recentRssiByDevice.getOrPut(stableId) { ArrayDeque(10) }
        if (history.size >= 10) history.removeFirst()
        history.addLast(rssi)
    }

    private fun computeOverallConfidence(obs: SignalObservation, trend: SignalTrend): Float {
        val trendBoost = if (trend != SignalTrend.UNKNOWN) 0.1f else 0f
        return (obs.confidence + trendBoost).coerceIn(0f, 1f)
    }

    private fun mergeDeviceInfo(existing: DeviceInfo, obs: SignalObservation): DeviceInfo {
        val meta = obs.metadata
        val name = when (meta) {
            is BleMetadata -> meta.deviceName ?: existing.displayName
            is WifiApMetadata -> meta.ssid ?: existing.displayName
            is NetworkMetadata -> meta.hostname ?: existing.displayName
        }
        val ip = (meta as? NetworkMetadata)?.ipAddress ?: existing.ipAddress
        val mdns = (meta as? NetworkMetadata)?.mdnsServiceTypes ?: existing.mdnsServices

        val protocols = existing.availableProtocols + obs.sourceType
        val inferredClass = inferDeviceClass(obs, existing)

        return existing.copy(
            displayName = name ?: existing.displayName,
            ipAddress = ip,
            mdnsServices = mdns,
            availableProtocols = protocols,
            inferredClass = inferredClass
        )
    }

    private fun inferDeviceClass(obs: SignalObservation, existing: DeviceInfo): DeviceClass {
        if (existing.inferredClass != DeviceClass.UNKNOWN) return existing.inferredClass
        return when (val meta = obs.metadata) {
            is WifiApMetadata -> DeviceClass.AP
            is BleMetadata -> when {
                meta.advertisingIntervalMs != null && meta.advertisingIntervalMs > 2000 -> DeviceClass.IOT
                meta.deviceName?.contains("watch", ignoreCase = true) == true -> DeviceClass.ACCESSORY
                meta.deviceName?.contains("buds", ignoreCase = true) == true -> DeviceClass.ACCESSORY
                meta.deviceName?.contains("headphone", ignoreCase = true) == true -> DeviceClass.ACCESSORY
                else -> DeviceClass.PHONE
            }
            else -> DeviceClass.UNKNOWN
        }
    }

    private fun pruneStaleDevices() {
        val now = System.currentTimeMillis()
        val staleThreshold = 30_000L // 30 seconds without observation
        beliefs.entries.removeIf { (_, belief) ->
            now - belief.lastSeenMs > staleThreshold
        }
    }

    private fun publishDevices() {
        _devices.value = beliefs.values
            .map { it.toTrackedDevice() }
            .sortedByDescending { it.proximityScore }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Lerp between two angles correctly handling 0/360 wrap-around */
    private fun angleLerp(from: Float, to: Float, t: Float): Float {
        var diff = to - from
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return (from + diff * t + 360f) % 360f
    }

    companion object {
        private const val CONVERGENCE_STEP = 0.05f
        private const val MAX_PROXIMITY_CHANGE_PER_TICK = 0.15f
        private const val ROTATION_SCORE_THRESHOLD = 0.55f
    }
}

// ─── Internal belief state (not exposed to UI) ────────────────────────────────

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
    fun toTrackedDevice(): TrackedDevice {
        val signals = observations
            .groupBy { it.sourceType }
            .map { (type, obs) ->
                val latest = obs.maxByOrNull { it.timestampMs }!!
                ActiveSignal(
                    type = type,
                    currentRssi = latest.rssi,
                    trend = SignalTrend.UNKNOWN // trend computed in engine
                )
            }

        return TrackedDevice(
            stableId = stableId,
            proximityScore = proximityScore,
            bearingDeg = bearingDeg,
            bearingConfidence = bearingConfidence,
            overallConfidence = overallConfidence,
            lastUpdatedMs = lastSeenMs,
            info = info.copy(
                knownMacs = macHistory.ifEmpty { listOf(activeMac) },
                macRotationCount = macRotationCount
            ),
            signals = signals
        )
    }
}
