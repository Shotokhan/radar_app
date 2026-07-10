package com.radar.app.domain

import com.radar.app.data.provider.FakeSignalProvider
import com.radar.app.domain.engine.ConvergenceEngine
import com.radar.app.domain.motion.FakeMotionTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConvergenceEngineTest {

    private lateinit var engine: ConvergenceEngine

    @Before
    fun setUp() {
        engine = ConvergenceEngine(FakeMotionTracker.walkingNorth())
    }

    @Test
    fun `walk toward device increases proximity score on average`() = runTest {
        val provider = FakeSignalProvider.walkingToward(
            mac = "AA:BB:CC:DD:EE:FF",
            steps = 10,
            startRssi = -85,
            endRssi = -50
        )
        engine.addProvider(provider)
        engine.start()

        advanceTimeBy(2000)

        val devices = engine.devices.value
        assertFalse("Should have tracked at least one device", devices.isEmpty())

        val device = devices.first()
        assertTrue(
            "Proximity score should be positive after walk-toward: ${device.proximityScore}",
            device.proximityScore > 0f
        )
    }

    @Test
    fun `proximity score never changes by more than max per tick`() = runTest {
        val provider = FakeSignalProvider.walkingToward(steps = 20)
        engine.addProvider(provider)
        engine.start()

        var lastScore = 0f
        val scores = mutableListOf<Float>()

        repeat(20) {
            advanceTimeBy(150)
            engine.devices.value.firstOrNull()?.proximityScore?.let {
                scores.add(it)
            }
        }

        for (i in 1 until scores.size) {
            val delta = Math.abs(scores[i] - scores[i - 1])
            assertTrue(
                "Score changed by $delta in one tick — exceeds max 0.15",
                delta <= 0.16f // slight tolerance for float precision
            )
        }
    }

    @Test
    fun `MAC rotation preserves stable device identity`() = runTest {
        val provider = FakeSignalProvider.withMacRotation(
            oldMac = "AA:BB:CC:DD:EE:FF",
            newMac = "11:22:33:44:55:66",
            stepsBeforeRotation = 5,
            stepsAfterRotation = 5
        )
        engine.addProvider(provider)
        engine.start()

        advanceTimeBy(600)

        val devices = engine.devices.value
        // Should have merged into ONE device, not two
        assertEquals(
            "MAC rotation should result in 1 device, got ${devices.size}",
            1,
            devices.size
        )

        val device = devices.first()
        assertTrue(
            "Device should have recorded a MAC rotation",
            device.info.macRotationCount >= 1
        )
    }

    @Test
    fun `devices are pruned after signal loss`() = runTest {
        val provider = FakeSignalProvider.walkingToward(steps = 3)
        engine.addProvider(provider)
        engine.start()

        advanceTimeBy(500)
        assertFalse(engine.devices.value.isEmpty())

        // Simulate 35 seconds of silence (stale threshold is 30s)
        advanceTimeBy(35_000)

        assertTrue(
            "Stale devices should have been pruned",
            engine.devices.value.isEmpty()
        )
    }

    @Test
    fun `protocol filter drops observations from disabled types`() = runTest {
        val bleProvider = FakeSignalProvider.walkingToward()
        engine.addProvider(bleProvider)
        engine.start()

        // Disable BLE
        engine.setActiveProtocols(emptySet())
        advanceTimeBy(2000)

        // Should have no devices since BLE is filtered out
        assertTrue(engine.devices.value.isEmpty())
    }
}
