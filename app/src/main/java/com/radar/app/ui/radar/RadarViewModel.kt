package com.radar.app.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radar.app.data.model.SignalType
import com.radar.app.data.model.TrackedDevice
import com.radar.app.data.provider.SignalProvider
import com.radar.app.domain.engine.ConvergenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RadarViewMode { FLAT_2D, SPHERE_3D }

data class RadarUiState(
    val devices: List<TrackedDevice> = emptyList(),
    val viewMode: RadarViewMode = RadarViewMode.FLAT_2D,
    val zoomLevel: Float = 1f,
    val activeProtocols: Set<SignalType> = SignalType.values().toSet(),
    val selectedDeviceId: String? = null,
    val isScanning: Boolean = false,
    val hasPermissions: Boolean = false
)

@HiltViewModel
class RadarViewModel @Inject constructor(
    private val engine: ConvergenceEngine,
    private val providers: Set<@JvmSuppressWildcards SignalProvider>
) : ViewModel() {

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            engine.devices.collect { devices ->
                _uiState.value = _uiState.value.copy(devices = devices)
            }
        }
    }

    fun onPermissionsGranted() {
        _uiState.value = _uiState.value.copy(hasPermissions = true, isScanning = true)
        providers.forEach { engine.addProvider(it) }
        engine.start()
    }

    fun onPermissionsDenied() {
        _uiState.value = _uiState.value.copy(hasPermissions = false, isScanning = false)
    }

    fun toggleViewMode() {
        val next = when (_uiState.value.viewMode) {
            RadarViewMode.FLAT_2D -> RadarViewMode.SPHERE_3D
            RadarViewMode.SPHERE_3D -> RadarViewMode.FLAT_2D
        }
        _uiState.value = _uiState.value.copy(viewMode = next)
    }

    fun setZoom(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoomLevel = zoom.coerceIn(0.3f, 3f))
    }

    fun selectDevice(stableId: String?) {
        _uiState.value = _uiState.value.copy(selectedDeviceId = stableId)
    }

    fun toggleProtocol(type: SignalType) {
        val current = _uiState.value.activeProtocols.toMutableSet()
        if (type in current) current.remove(type) else current.add(type)
        _uiState.value = _uiState.value.copy(activeProtocols = current)
        engine.setActiveProtocols(current)
    }

    fun getSelectedDevice(): TrackedDevice? {
        val id = _uiState.value.selectedDeviceId ?: return null
        return _uiState.value.devices.find { it.stableId == id }
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}
