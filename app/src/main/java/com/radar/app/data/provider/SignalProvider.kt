package com.radar.app.data.provider

import com.radar.app.data.model.Capability
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Layer 1 contract. Each scanner (BLE, Wi-Fi AP, Network) implements this.
 * The ConvergenceEngine only knows about this interface — never about concrete providers.
 */
interface SignalProvider {
    val type: SignalType
    val observations: Flow<SignalObservation>
    val isAvailable: StateFlow<Boolean>
    val requiredCapabilities: Set<Capability>

    fun start()
    fun stop()
}
