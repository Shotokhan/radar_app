package com.radar.app.domain

import com.radar.app.data.model.BleMetadata
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalType
import com.radar.app.data.provider.FakeSignalProvider
import com.radar.app.di.ApplicationScope
import com.radar.app.domain.engine.ConvergenceEngine
import com.radar.app.domain.motion.FakeMotionTracker
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ConvergenceEngine.
 * We bypass Hilt entirely in unit tests — no @HiltAndroidTest needed.
 * The engine is constructed directly with fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConvergenceEngineTest {

    private fun buildEngine(): ConvergenceEngine {
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher())
        // Use reflection to bypass the @ApplicationScope qualifier for unit tests
        return ConvergenceEngine::class.java
            .getDeclaredConstructor(
                com.radar.app.domain.motion.MotionTracker::class.java,
                kotlinx.coroutines.CoroutineScope::class.java
            )
            .also { it.isAccessible = true }
            .newInstance(FakeMotionTracker.walkingNorth(), scope)
    }

    private fun makeObs(mac: String, rssi: Int, ts: Long = System.currentTimeMillis()) =
        SignalObservation(
            sourceId = mac, sourceType = SignalType.BLE, timestampMs = ts,
            rssi = rssi, normalizedSignal = ((rssi + 100f) / 60f).coerceIn(0f, 1f),
            confidence = 0.9f, metadata = BleMetadata(deviceName = "Test", isMacRandomized = false)
        )

    private fun ConvergenceEngine.feed(vararg obs: SignalObservation) =
        addProvider(FakeSignalProvider(script = obs.toList(), emitDelayMs = 0))

    @Test
    fun `walk toward device increases proximity score`() = runTest {
        val engine = buildEngine()
        val now = System.currentTimeMillis()
        engine.feed(*Array(10) { i -> makeObs("AA:BB:CC:DD:EE:FF", rssi = -85 + i * 4, ts = now + i * 1000L) })

        val devices = engine.devices.value
        assertFalse("Should track at least one device", devices.isEmpty())
        assertTrue("Proximity should be positive after approach, was ${devices.first().proximityScore}",
            devices.first().proximityScore > 0f)
    }

    @Test
    fun `proximity score never jumps more than max per observation`() = runTest {
        val engine = buildEngine()
        val now = System.currentTimeMillis()
        val scores = mutableListOf<Float>()

        for (i in 0 until 15) {
            engine.feed(makeObs("AA:BB:CC:DD:EE:FF", rssi = -85 + i * 3, ts = now + i * 1000L))
            engine.devices.value.firstOrNull()?.proximityScore?.let { scores.add(it) }
        }

        assertTrue("Need at least 2 score samples", scores.size >= 2)
        for (i in 1 until scores.size) {
            val delta = kotlin.math.abs(scores[i] - scores[i - 1])
            assertTrue("Score jumped $delta at step $i — exceeds 0.15", delta <= 0.16f)
        }
    }

    @Test
    fun `MAC rotation preserves stable device identity`() = runTest {
        val engine = buildEngine()
        val now = System.currentTimeMillis()

        // Phase 1: establish device with old MAC
        engine.feed(*Array(5) { i -> makeObs("AA:BB:CC:DD:EE:FF", rssi = -60, ts = now + i * 1000L) })
        val idBefore = engine.devices.value.firstOrNull()?.stableId
        assertNotNull("Should have a device after phase 1", idBefore)

        // Phase 2: same device, rotated MAC, within 60s
        engine.feed(*Array(5) { i -> makeObs("11:22:33:44:55:66", rssi = -61, ts = now + (5 + i) * 1000L) })

        val devicesAfter = engine.devices.value
        assertEquals("Should still be 1 device after MAC rotation", 1, devicesAfter.size)
        assertEquals("stableId should survive MAC rotation", idBefore, devicesAfter.first().stableId)
        assertTrue("macRotationCount should be >= 1", devicesAfter.first().info.macRotationCount >= 1)
    }

    @Test
    fun `protocol filter drops observations from disabled types`() = runTest {
        val engine = buildEngine()
        engine.setActiveProtocols(emptySet())
        engine.feed(*Array(5) { i -> makeObs("AA:BB:CC:DD:EE:FF", rssi = -60 + i) })
        assertTrue("No devices when BLE filtered out", engine.devices.value.isEmpty())
    }

    @Test
    fun `two simultaneous devices are tracked separately`() = runTest {
        val engine = buildEngine()
        val now = System.currentTimeMillis()
        val obs1 = Array(3) { i -> makeObs("AA:BB:CC:DD:EE:01", rssi = -70, ts = now + i * 100L) }
        val obs2 = Array(3) { i -> makeObs("AA:BB:CC:DD:EE:02", rssi = -75, ts = now + i * 100L) }
        engine.feed(*(obs1 + obs2))
        assertEquals("Two distinct MACs → 2 devices", 2, engine.devices.value.size)
    }
}
