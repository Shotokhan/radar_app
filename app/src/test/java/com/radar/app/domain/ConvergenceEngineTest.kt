package com.radar.app.domain

import com.radar.app.data.model.BleMetadata
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalType
import com.radar.app.data.provider.FakeSignalProvider
import com.radar.app.domain.engine.ConvergenceEngine
import com.radar.app.domain.motion.FakeMotionTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConvergenceEngineTest {

    // Feed observations directly into the engine without going through a provider flow,
    // so tests are fully synchronous and deterministic.
    private fun buildEngine(): ConvergenceEngine {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        return ConvergenceEngine(FakeMotionTracker.walkingNorth(), scope)
    }

    /** Feed a sequence of observations directly, bypassing provider Flow timing. */
    private fun ConvergenceEngine.feedObservations(vararg obs: SignalObservation) {
        // Expose via a test-only helper: use the fake provider with pre-built script
        // but collect synchronously by calling addProvider with an already-collected list.
        // Since UnconfinedTestDispatcher runs eagerly, flow collection is immediate.
        val provider = FakeSignalProvider(script = obs.toList(), emitDelayMs = 0)
        addProvider(provider)
    }

    private fun makeObs(mac: String, rssi: Int, ts: Long = System.currentTimeMillis()) =
        SignalObservation(
            sourceId = mac,
            sourceType = SignalType.BLE,
            timestampMs = ts,
            rssi = rssi,
            normalizedSignal = ((rssi + 100f) / 60f).coerceIn(0f, 1f),
            confidence = 0.9f,
            metadata = BleMetadata(deviceName = "TestDevice", isMacRandomized = false)
        )

    @Test
    fun `walk toward device increases proximity score over time`() = runTest {
        val engine = buildEngine()

        // Feed 10 observations with steadily increasing RSSI (approaching)
        val now = System.currentTimeMillis()
        val obs = (0 until 10).map { i ->
            makeObs("AA:BB:CC:DD:EE:FF", rssi = -85 + i * 4, ts = now + i * 1000L)
        }
        engine.feedObservations(*obs.toTypedArray())

        // Give UnconfinedTestDispatcher time to process
        val devices = engine.devices.value
        assertFalse("Should have at least one tracked device", devices.isEmpty())

        val device = devices.first()
        assertTrue(
            "Proximity should be positive after approaching observations, was ${device.proximityScore}",
            device.proximityScore > 0f
        )
    }

    @Test
    fun `proximity score never jumps more than max per observation`() = runTest {
        val engine = buildEngine()
        val now = System.currentTimeMillis()

        val scores = mutableListOf<Float>()
        // Feed one observation at a time and sample the score after each
        for (i in 0 until 15) {
            val obs = makeObs("AA:BB:CC:DD:EE:FF", rssi = -85 + i * 3, ts = now + i * 1000L)
            engine.feedObservations(obs)
            engine.devices.value.firstOrNull()?.proximityScore?.let { scores.add(it) }
        }

        assertTrue("Need at least 2 score samples", scores.size >= 2)
        for (i in 1 until scores.size) {
            val delta = kotlin.math.abs(scores[i] - scores[i - 1])
            assertTrue(
                "Score jumped by $delta at step $i — exceeds max 0.15",
                delta <= 0.16f
            )
        }
    }

    @Test
    fun `MAC rotation preserves stable device identity`() = runTest {
        val engine = buildEngine()
        val now = System.currentTimeMillis()

        // Phase 1: 5 observations with old MAC
        val oldObs = (0 until 5).map { i ->
            makeObs("AA:BB:CC:DD:EE:FF", rssi = -60, ts = now + i * 1000L)
        }
        engine.feedObservations(*oldObs.toTypedArray())

        val devicesBefore = engine.devices.value
        assertEquals("Should have 1 device before rotation", 1, devicesBefore.size)
        val stableIdBefore = devicesBefore.first().stableId

        // Phase 2: 5 observations with new MAC, shortly after (within 60s window)
        val newObs = (0 until 5).map { i ->
            makeObs("11:22:33:44:55:66", rssi = -61, ts = now + (5 + i) * 1000L)
        }
        engine.feedObservations(*newObs.toTypedArray())

        val devicesAfter = engine.devices.value
        assertEquals("Should still have 1 device after MAC rotation", 1, devicesAfter.size)
        assertEquals(
            "stableId should be preserved across MAC rotation",
            stableIdBefore,
            devicesAfter.first().stableId
        )
        assertTrue(
            "macRotationCount should be >= 1",
            devicesAfter.first().info.macRotationCount >= 1
        )
    }

    @Test
    fun `protocol filter drops observations from disabled types`() = runTest {
        val engine = buildEngine()
        // Disable BLE before adding any observations
        engine.setActiveProtocols(emptySet())

        val obs = (0 until 5).map { i -> makeObs("AA:BB:CC:DD:EE:FF", rssi = -60 + i) }
        engine.feedObservations(*obs.toTypedArray())

        assertTrue(
            "No devices should be tracked when BLE is filtered out",
            engine.devices.value.isEmpty()
        )
    }

    @Test
    fun `two distinct MACs with no temporal overlap create two separate devices`() = runTest {
        val engine = buildEngine()
        val now = System.currentTimeMillis()

        // Two different devices seen simultaneously — should NOT be merged
        val obs1 = (0 until 3).map { i -> makeObs("AA:BB:CC:DD:EE:01", rssi = -70, ts = now + i * 100L) }
        val obs2 = (0 until 3).map { i -> makeObs("AA:BB:CC:DD:EE:02", rssi = -75, ts = now + i * 100L) }

        engine.feedObservations(*(obs1 + obs2).toTypedArray())

        val devices = engine.devices.value
        assertEquals("Two distinct MACs seen simultaneously should produce 2 devices", 2, devices.size)
    }
}
