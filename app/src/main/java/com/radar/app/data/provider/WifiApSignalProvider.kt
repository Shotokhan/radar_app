package com.radar.app.data.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.radar.app.data.model.Capability
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalType
import com.radar.app.data.model.WifiApMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiApSignalProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : SignalProvider {

    override val type = SignalType.WIFI_AP
    override val requiredCapabilities = setOf(Capability.WIFI_SCAN)

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    override val observations: Flow<SignalObservation> = callbackFlow {
        val manager = wifiManager
        if (manager == null || !manager.isWifiEnabled) {
            _isAvailable.value = false
            close()
            return@callbackFlow
        }

        _isAvailable.value = true

        val connectedBssid = manager.connectionInfo?.bssid

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return

                @Suppress("DEPRECATION")
                val results = manager.scanResults ?: return

                results.forEach { result ->
                    // Normalize RSSI: Wi-Fi range typically -100 to -30 dBm
                    val normalized = ((result.level + 100f) / 70f).coerceIn(0f, 1f)

                    val metadata = WifiApMetadata(
                        ssid = result.SSID.takeIf { it.isNotBlank() },
                        bssid = result.BSSID,
                        frequencyMhz = result.frequency,
                        isConnected = result.BSSID == connectedBssid,
                        channelWidth = null
                    )

                    val observation = SignalObservation(
                        sourceId = result.BSSID,
                        sourceType = SignalType.WIFI_AP,
                        timestampMs = System.currentTimeMillis(),
                        rssi = result.level,
                        normalizedSignal = normalized,
                        confidence = 0.85f,
                        metadata = metadata
                    )
                    trySend(observation)
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        // Trigger first scan
        @Suppress("DEPRECATION")
        manager.startScan()

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    override fun start() {
        _isAvailable.value = wifiManager?.isWifiEnabled == true
        @Suppress("DEPRECATION")
        wifiManager?.startScan()
    }

    override fun stop() {
        // Scan stops automatically; receiver is closed via Flow lifecycle
    }
}
