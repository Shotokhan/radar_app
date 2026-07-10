package com.radar.app.data.provider

import com.radar.app.data.model.BleMetadata
import com.radar.app.data.model.Capability
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Scriptable fake provider for unit tests and the convergence engine.
 * Feed it a list of observations; it emits them with optional delay.
 */
class FakeSignalProvider(
    override val type: SignalType = SignalType.BLE,
    private val script: List<SignalObservation> = emptyList(),
    private val emitDelayMs: Long = 100L,
    initialAvailable: Boolean = true
) : SignalProvider {

    override val requiredCapabilities: Set<Capability> = emptySet()

    private val _isAvailable = MutableStateFlow(initialAvailable)
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    override val observations: Flow<SignalObservation> = flow {
        script.forEach { obs ->
            delay(emitDelayMs)
            emit(obs)
        }
    }

    override fun start() { _isAvailable.value = true }
    override fun stop() { _isAvailable.value = false }

    companion object {
        /** Helper: build a walk-toward script that increases signal over N steps */
        fun walkingToward(
            mac: String = "AA:BB:CC:DD:EE:FF",
            steps: Int = 10,
            startRssi: Int = -85,
            endRssi: Int = -50
        ): FakeSignalProvider {
            val script = (0 until steps).map { i ->
                val rssi = startRssi + ((endRssi - startRssi) * i / steps.toFloat()).toInt()
                val normalized = ((rssi + 100f) / 60f).coerceIn(0f, 1f)
                SignalObservation(
                    sourceId = mac,
                    sourceType = SignalType.BLE,
                    timestampMs = System.currentTimeMillis() + i * 1000L,
                    rssi = rssi,
                    normalizedSignal = normalized,
                    confidence = 0.8f,
                    metadata = BleMetadata(deviceName = "TestDevice", isMacRandomized = false)
                )
            }
            return FakeSignalProvider(script = script)
        }

        /** Simulates a MAC rotation: same device, new address mid-sequence */
        fun withMacRotation(
            oldMac: String = "AA:BB:CC:DD:EE:FF",
            newMac: String = "11:22:33:44:55:66",
            stepsBeforeRotation: Int = 5,
            stepsAfterRotation: Int = 5
        ): FakeSignalProvider {
            val script = buildList {
                repeat(stepsBeforeRotation) { i ->
                    add(SignalObservation(
                        sourceId = oldMac, sourceType = SignalType.BLE,
                        timestampMs = System.currentTimeMillis() + i * 1000L,
                        rssi = -60, normalizedSignal = 0.67f, confidence = 0.9f,
                        metadata = BleMetadata(deviceName = "Phone", isMacRandomized = false)
                    ))
                }
                repeat(stepsAfterRotation) { i ->
                    add(SignalObservation(
                        sourceId = newMac, sourceType = SignalType.BLE,
                        timestampMs = System.currentTimeMillis() + (stepsBeforeRotation + i) * 1000L,
                        rssi = -61, normalizedSignal = 0.65f, confidence = 0.9f,
                        metadata = BleMetadata(deviceName = "Phone", isMacRandomized = true)
                    ))
                }
            }
            return FakeSignalProvider(script = script)
        }
    }
}
