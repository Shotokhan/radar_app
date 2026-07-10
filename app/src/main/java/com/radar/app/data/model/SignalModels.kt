package com.radar.app.data.model

import java.util.UUID

// ─── Signal Types ────────────────────────────────────────────────────────────

enum class SignalType {
    BLE,
    WIFI_AP,
    WIFI_CLIENT,
    NETWORK
}

enum class Capability {
    BLE_SCAN,
    WIFI_SCAN,
    WIFI_MONITOR_MODE,
    USB_HOST,
    NETWORK_SCAN,
    ROOT_ACCESS
}

// ─── Signal Metadata (sealed, one subtype per provider) ──────────────────────

sealed class SignalMetadata

data class BleMetadata(
    val deviceName: String? = null,
    val manufacturerOui: String? = null,
    val serviceUuids: List<UUID> = emptyList(),
    val advertisingIntervalMs: Int? = null,
    val txPower: Int? = null,
    val isMacRandomized: Boolean = false
) : SignalMetadata()

data class WifiApMetadata(
    val ssid: String? = null,
    val bssid: String,
    val frequencyMhz: Int,
    val isConnected: Boolean = false,
    val channelWidth: Int? = null
) : SignalMetadata()

data class NetworkMetadata(
    val ipAddress: String,
    val macAddress: String? = null,
    val hostname: String? = null,
    val mdnsServiceTypes: List<String> = emptyList()
) : SignalMetadata()

// ─── Core observation emitted by all providers ───────────────────────────────

data class SignalObservation(
    val sourceId: String,
    val sourceType: SignalType,
    val timestampMs: Long,
    val rssi: Int? = null,
    /** Provider-normalized 0.0–1.0 signal strength */
    val normalizedSignal: Float,
    /** 0.0–1.0: scan quality, antenna reliability, environment */
    val confidence: Float,
    val metadata: SignalMetadata
) {
    init {
        require(normalizedSignal in 0f..1f) { "normalizedSignal must be in [0,1]" }
        require(confidence in 0f..1f) { "confidence must be in [0,1]" }
    }
}

// ─── Signal Provider interface (Layer 1) ─────────────────────────────────────

// Implemented in data/provider package; consumed by ConvergenceEngine
