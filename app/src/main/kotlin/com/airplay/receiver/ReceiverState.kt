package com.airplay.receiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for UI. The service writes; Compose screens collect.
 * Deliberately tiny and process-global — the service and the Activities live in
 * the same process, so a plain object with StateFlows is the simplest correct
 * bridge (no IPC, survives Activity recreation).
 */
object ReceiverState {

    sealed interface Status {
        data object Starting : Status
        /** Advertising on the network, waiting for a sender. */
        data class Ready(val deviceName: String, val ip: String?) : Status
        /** A sender is mirroring / playing. */
        data class Connected(val sender: String, val what: Session) : Status
        data class Error(val message: String) : Status
    }

    enum class Session { MIRRORING, AUDIO }

    private val _status = MutableStateFlow<Status>(Status.Starting)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun setReady(deviceName: String, ip: String?) { _status.value = Status.Ready(deviceName, ip) }
    fun setConnected(sender: String, what: Session) { _status.value = Status.Connected(sender, what) }
    fun setError(msg: String) { _status.value = Status.Error(msg) }
}
