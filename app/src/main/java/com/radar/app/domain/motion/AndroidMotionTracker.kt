package com.radar.app.domain.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.radar.app.data.model.MotionSample
import com.radar.app.data.model.Vector2D
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class AndroidMotionTracker @Inject constructor(
    @ApplicationContext private val context: Context
) : MotionTracker {

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val _totalDisplacement = MutableStateFlow(Vector2D.ZERO)
    override val totalDisplacement: StateFlow<Vector2D> = _totalDisplacement

    // Rolling window for noise floor estimation
    private val rssiWindow = ArrayDeque<Float>(20)
    private var lastTimestampNs = 0L
    private var headingDeg = 0f
    private var headingConfidence = 0f
    private var isStationary = true
    private var accumulatedDisplacement = Vector2D.ZERO

    // Low-pass filter state for accelerometer
    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)
    private val alpha = 0.8f

    // Magnetometer + accelerometer fusion for heading
    private val accelData = FloatArray(3)
    private val magData = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Step detection
    private var stepVelocity = 0f
    private val stepThreshold = 1.2f // m/s²
    private val stepLength = 0.7f    // meters, average adult step

    override val samples: Flow<MotionSample> = callbackFlow {
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
                    Sensor.TYPE_MAGNETIC_FIELD -> processMagnetometer(event)
                    Sensor.TYPE_GYROSCOPE -> processGyroscope(event)
                }

                val nowMs = System.currentTimeMillis()
                val dt = if (lastTimestampNs > 0)
                    (event.timestamp - lastTimestampNs) / 1_000_000_000f
                else 0f
                lastTimestampNs = event.timestamp

                // Compute step-based displacement
                val stepDelta = computeStepDelta(dt)

                accumulatedDisplacement = accumulatedDisplacement + stepDelta
                _totalDisplacement.value = accumulatedDisplacement

                // Estimate motion gate from recent accel variance
                val motionGate = estimateMotionGate()

                val sample = MotionSample(
                    timestampMs = nowMs,
                    deltaMeters = stepDelta,
                    headingDeg = headingDeg,
                    headingConfidence = headingConfidence,
                    isStationary = isStationary,
                    motionGate = motionGate
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    headingConfidence = when (accuracy) {
                        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 0.9f
                        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.6f
                        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.3f
                        else -> 0.1f
                    }
                }
            }
        }

        // Register at game rate for smooth tracking
        val rate = SensorManager.SENSOR_DELAY_GAME
        accelSensor?.let { sensorManager.registerListener(listener, it, rate) }
        magSensor?.let { sensorManager.registerListener(listener, it, rate) }
        gyroSensor?.let { sensorManager.registerListener(listener, it, rate) }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    private fun processAccelerometer(event: SensorEvent) {
        // Isolate gravity with low-pass filter
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        linearAccel[0] = event.values[0] - gravity[0]
        linearAccel[1] = event.values[1] - gravity[1]
        linearAccel[2] = event.values[2] - gravity[2]

        accelData[0] = event.values[0]
        accelData[1] = event.values[1]
        accelData[2] = event.values[2]

        // Detect if stationary (low linear acceleration magnitude)
        val accelMag = linearAccel.fold(0f) { acc, v -> acc + v * v }
        isStationary = accelMag < 0.5f

        // Step detection via peak detection on vertical acceleration
        val vertAccel = abs(linearAccel[2])
        stepVelocity = stepVelocity * 0.9f + vertAccel * 0.1f

        updateHeading()
    }

    private fun processMagnetometer(event: SensorEvent) {
        magData[0] = event.values[0]
        magData[1] = event.values[1]
        magData[2] = event.values[2]
        updateHeading()
    }

    private fun processGyroscope(event: SensorEvent) {
        // Gyroscope used for heading smoothing when magnetometer confidence is low
        // Future: full complementary filter here
    }

    private fun updateHeading() {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix, null, accelData, magData
        )
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            // orientationAngles[0] = azimuth in radians, 0 = North, increases clockwise
            val azimuthRad = orientationAngles[0]
            headingDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                .let { if (it < 0) it + 360f else it }
        }
    }

    private fun computeStepDelta(dt: Float): Vector2D {
        if (isStationary || stepVelocity < stepThreshold || dt <= 0f) return Vector2D.ZERO

        // Project step along current heading
        val headingRad = Math.toRadians(headingDeg.toDouble())
        val stepX = (sin(headingRad) * stepLength * dt).toFloat()
        val stepY = (cos(headingRad) * stepLength * dt).toFloat()
        return Vector2D(stepX, stepY)
    }

    private fun estimateMotionGate(): Float {
        // Returns a noise floor estimate in "dB equivalent"
        // Higher when stationary (more noise filtering needed), lower when moving
        return if (isStationary) 10f else 5f
    }

    override fun start() {
        accumulatedDisplacement = Vector2D.ZERO
        _totalDisplacement.value = Vector2D.ZERO
        lastTimestampNs = 0L
    }

    override fun stop() {
        // Sensor unregistration handled by Flow's awaitClose
    }

    override fun calibrate() {
        accumulatedDisplacement = Vector2D.ZERO
        _totalDisplacement.value = Vector2D.ZERO
        lastTimestampNs = 0L
        gravity.fill(0f)
        stepVelocity = 0f
    }
}
