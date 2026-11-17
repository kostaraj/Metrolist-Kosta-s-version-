package com.metrolist.shared.youtube.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackNetworkState(
    val scope: String,
    val connected: Boolean = true,
    val isCellular: Boolean = false,
) {
    init {
        require(scope.length <= 128) { "Network scope is too long" }
        require(scope.none { it.isWhitespace() }) { "Network scope must be normalized" }
    }
}

object PlaybackNetworkStateStore {
    private val _state = MutableStateFlow(PlaybackNetworkState(scope = "", connected = true))
    private var latestActualState = _state.value
    private var forcedOffline = false
    private val _actualState = MutableStateFlow(latestActualState)
    val state: StateFlow<PlaybackNetworkState> = _state.asStateFlow()
    val actualState: StateFlow<PlaybackNetworkState> = _actualState.asStateFlow()

    val current: PlaybackNetworkState
        get() = _state.value

    fun setForcedOffline(enabled: Boolean) {
        // Automatic offline mode always wins while the real network is unavailable.
        if (!latestActualState.connected && !enabled) return
        if (forcedOffline == enabled) return
        forcedOffline = enabled
        publishEffectiveState()
    }

    fun publish(state: PlaybackNetworkState) {
        latestActualState = state
        _actualState.value = state
        publishEffectiveState()
    }

    private fun publishEffectiveState() {
        _state.value =
            if (forcedOffline) {
                latestActualState.copy(scope = "forced-offline", connected = false, isCellular = false)
            } else {
                latestActualState
            }
    }
}
