package com.radar.app.data.model

data class Vector2D(
    /** Meters East (+) / West (-) */
    val x: Float,
    /** Meters North (+) / South (-) */
    val y: Float
) {
    val magnitude: Float get() = Math.sqrt((x * x + y * y).toDouble()).toFloat()

    operator fun plus(other: Vector2D) = Vector2D(x + other.x, y + other.y)
    operator fun times(scale: Float) = Vector2D(x * scale, y * scale)

    companion object {
        val ZERO = Vector2D(0f, 0f)
    }
}

data class MotionSample(
    val timestampMs: Long,
    /** Relative displacement since last sample */
    val deltaMeters: Vector2D,
    /** 0–360 degrees, magnetic North, fused from magnetometer + gyroscope */
    val headingDeg: Float,
    /** 0–1; degrades near magnetic interference */
    val headingConfidence: Float,
    /** True if no significant motion detected this sample */
    val isStationary: Boolean,
    /** Noise floor estimate in dB — engine ignores RSSI delta below this */
    val motionGate: Float
) {
    companion object {
        val STATIONARY = MotionSample(
            timestampMs = System.currentTimeMillis(),
            deltaMeters = Vector2D.ZERO,
            headingDeg = 0f,
            headingConfidence = 0f,
            isStationary = true,
            motionGate = 8f
        )
    }
}
