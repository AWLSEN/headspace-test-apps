package com.mercurylabs.headspace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable state of the recorder. RecordingService writes
 * to it; the UI subscribes. Avoids needing a bound service interface.
 */
object RecorderState {
    enum class Phase {
        IDLE,             // no Headspace device attached
        DEVICE_READY,     // device attached, not recording
        RECORDING,        // recording in progress
    }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val deviceName: String = "",
        val sessionName: String = "",
        val sessionDirPath: String = "",
        val bytesWritten: Long = 0L,
        val imuSamples: Long = 0L,
        val imuHz: Double = 0.0,
        val mbps: Double = 0.0,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state = _state.asStateFlow()

    /** Set by the UI; read by RecordingService to push decoded frames. */
    @Volatile var previewSurface: android.view.Surface? = null
    /** Set by RecordingService; read by UI to know decoder state. */
    @Volatile var framesDecoded: Long = 0

    fun setIdle() {
        _state.value = Snapshot()
    }

    fun setReady(deviceName: String) {
        _state.value = _state.value.copy(
            phase = Phase.DEVICE_READY,
            deviceName = deviceName,
            lastError = null,
        )
    }

    fun setRecording(sessionDir: String, sessionName: String, deviceName: String) {
        _state.value = _state.value.copy(
            phase = Phase.RECORDING,
            sessionName = sessionName,
            sessionDirPath = sessionDir,
            deviceName = deviceName,
            bytesWritten = 0L, imuSamples = 0L, imuHz = 0.0, mbps = 0.0,
            lastError = null,
        )
    }

    fun updateStats(bytes: Long, imu: Long, hz: Double, mbps: Double) {
        _state.value = _state.value.copy(
            bytesWritten = bytes, imuSamples = imu, imuHz = hz, mbps = mbps,
        )
    }

    fun setError(msg: String) {
        _state.value = _state.value.copy(lastError = msg)
    }
}
