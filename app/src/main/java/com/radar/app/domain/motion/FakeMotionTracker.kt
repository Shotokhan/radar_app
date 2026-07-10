package com.radar.app.domain.motion

import com.radar.app.data.model.MotionSample
import com.radar.app.data.model.Vector2D
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Scriptable fake motion tracker for unit tests.
 * Feed it a walk path as a list of MotionSamples.
 */
class FakeMotionTracker(
    private val script: List<MotionSample> = emptyList(),
    private val emitDelayMs: Long = 100L
) : MotionTracker {

    private val _totalDisplacement = MutableStateFlow(Vector2D.ZERO)
    override val totalDisplacement: StateFlow<Vector2D> = _totalDisplacement

    override val samples: Flow<MotionSample> = flow {
        var cumulative = Vector2D.ZERO
        script.forEach { sample ->
            delay(emitDelayMs)
            cumulative = cumulative + sample.deltaMeters
            _totalDisplacement.value = cumulative
            emit(sample)
        }
    }

    override fun start() { _totalDisplacement.value = Vector2D.ZERO }
    override fun stop() {}
    override fun calibrate() { _totalDisplacement.value = Vector2D.ZERO }

    companion object {
        /** A straight walk North for N steps */
        fun walkingNorth(steps: Int = 10, stepSize: Float = 0.7f): FakeMotionTracker {
            val script = (0 until steps).map { i ->
                MotionSample(
                    timestampMs = System.currentTimeMillis() + i * 100L,
                    deltaMeters = Vector2D(0f, stepSize),
                    headingDeg = 0f,
                    headingConfidence = 0.9f,
                    isStationary = false,
                    motionGate = 5f
                )
            }
            return FakeMotionTracker(script)
        }

        /** Stationary — emits a single stationary sample */
        fun stationary(): FakeMotionTracker =
            FakeMotionTracker(listOf(MotionSample.STATIONARY))
    }
}
