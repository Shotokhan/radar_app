package com.radar.app.data.provider

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.radar.app.data.model.BleMetadata
import com.radar.app.data.model.Capability
import com.radar.app.data.model.SignalObservation
import com.radar.app.data.model.SignalType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleSignalProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : SignalProvider {

    override val type = SignalType.BLE
    override val requiredCapabilities = setOf(Capability.BLE_SCAN)

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val bleScanner get() = bluetoothAdapter?.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null

    override val observations: Flow<SignalObservation> = callbackFlow {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _isAvailable.value = false
            close()
            return@callbackFlow
        }

        _isAvailable.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val record = result.scanRecord

                // Normalize RSSI: typical range -100 dBm (weak) to -40 dBm (strong)
                val normalized = ((result.rssi + 100f) / 60f).coerceIn(0f, 1f)

                val serviceUuids: List<UUID> = record?.serviceUuids
                    ?.mapNotNull { (it as? ParcelUuid)?.uuid }
                    ?: emptyList()

                val macBytes = device.address.split(":").map { it.toInt(16) }
                val oui = if (macBytes.size >= 3)
                    "%02X:%02X:%02X".format(macBytes[0], macBytes[1], macBytes[2])
                else null

                // Detect randomized MAC: locally administered bit (bit 1 of first octet)
                val isRandomized = macBytes.isNotEmpty() && (macBytes[0] and 0x02) != 0

                val metadata = BleMetadata(
                    deviceName = record?.deviceName ?: device.name,
                    manufacturerOui = oui,
                    serviceUuids = serviceUuids,
                    txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
                    isMacRandomized = isRandomized
                )

                val observation = SignalObservation(
                    sourceId = device.address,
                    sourceType = SignalType.BLE,
                    timestampMs = System.currentTimeMillis(),
                    rssi = result.rssi,
                    normalizedSignal = normalized,
                    confidence = if (result.rssi > -80) 0.9f else 0.5f,
                    metadata = metadata
                )

                trySend(observation)
            }

            override fun onScanFailed(errorCode: Int) {
                _isAvailable.value = false
            }
        }

        scanCallback = callback

        try {
            bleScanner?.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            _isAvailable.value = false
            close(e)
        }

        awaitClose {
            try {
                bleScanner?.stopScan(callback)
            } catch (_: SecurityException) {}
            scanCallback = null
        }
    }

    override fun start() {
        // Flow is self-starting; this is a no-op stub for lifecycle hooks
        _isAvailable.value = bluetoothAdapter?.isEnabled == true
    }

    override fun stop() {
        scanCallback?.let {
            try { bleScanner?.stopScan(it) } catch (_: SecurityException) {}
        }
        scanCallback = null
    }
}
