package com.radar.app.domain.motion

import com.radar.app.data.model.MotionSample
import com.radar.app.data.model.Vector2D
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Layer 2 contract. Consumes hardware sensors; emits MotionSamples.
 * The ConvergenceEngine only knows about this interface.
 */
interface MotionTracker {
    val samples: Flow<MotionSample>
    /** Cumulative displacement since last calibrate() */
    val totalDisplacement: StateFlow<Vector2D>

    fun start()
    fun stop()
    /** Reset gyro drift baseline. Call once device is stable. */
    fun calibrate()
}
