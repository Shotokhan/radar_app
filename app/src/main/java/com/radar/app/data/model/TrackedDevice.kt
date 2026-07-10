package com.radar.app.data.model

enum class DeviceClass {
    PHONE, ACCESSORY, AP, IOT, UNKNOWN
}

enum class SignalTrend {
    APPROACHING, STABLE, RECEDING, UNKNOWN
}

data class ActiveSignal(
    val type: SignalType,
    val currentRssi: Int?,
    val trend: SignalTrend
)

data class DeviceInfo(
    val displayName: String? = null,
    val knownMacs: List<String> = emptyList(),
    val ipAddress: String? = null,
    val inferredClass: DeviceClass = DeviceClass.UNKNOWN,
    val availableProtocols: Set<SignalType> = emptySet(),
    val mdnsServices: List<String> = emptyList(),
    /** Non-null when BLE+WiFi sources were merged; value = merge confidence */
    val mergeConfidence: Float? = null,
    val firstSeenMs: Long = System.currentTimeMillis(),
    /** Number of MAC rotations detected — hints at device type */
    val macRotationCount: Int = 0
)

data class TrackedDevice(
    /** Engine-assigned UUID, stable across MAC rotations */
    val stableId: String,
    /** 0.0 = far / unknown, 1.0 = very close. NOT meters. */
    val proximityScore: Float,
    /** Estimated bearing in degrees (0–360); null until enough motion data */
    val bearingDeg: Float? = null,
    /** 0–1; UI renders cone width proportional to this */
    val bearingConfidence: Float = 0f,
    /** Composite confidence; drives opacity/size in UI */
    val overallConfidence: Float,
    val lastUpdatedMs: Long,
    val info: DeviceInfo,
    /** Which providers currently see this device */
    val signals: List<ActiveSignal> = emptyList()
) {
    /** Polar coordinates for radar canvas placement.
     *  angle = bearingDeg (0 = up/North), radius = 1 - proximityScore
     *  so higher proximity → closer to center */
    val radarRadius: Float get() = 1f - proximityScore.coerceIn(0f, 1f)
}
