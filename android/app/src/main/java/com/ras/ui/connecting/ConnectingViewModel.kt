package com.ras.ui.connecting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionLog
import com.ras.di.DefaultDispatcher
import com.ras.domain.startup.AttemptReconnectionUseCase
import com.ras.domain.startup.ReconnectionResult
import com.ras.domain.unpair.UnpairDeviceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the ConnectingScreen.
 *
 * Handles the connection flow with progress updates and retry capability.
 */
@HiltViewModel
class ConnectingViewModel @Inject constructor(
    private val attemptReconnectionUseCase: AttemptReconnectionUseCase,
    private val unpairDeviceUseCase: UnpairDeviceUseCase,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectingState>(ConnectingState.Connecting())
    val state: StateFlow<ConnectingState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConnectingUiEvent>()
    val events: SharedFlow<ConnectingUiEvent> = _events.asSharedFlow()

    init {
        connect()
    }

    /**
     * Start or restart connection attempt.
     */
    fun connect() {
        viewModelScope.launch {
            _state.value = ConnectingState.Connecting()
            attemptConnection()
        }
    }

    /**
     * Retry connection after failure.
     */
    fun retry() {
        connect()
    }

    /**
     * Navigate back to HomeScreen.
     */
    fun navigateBack() {
        viewModelScope.launch {
            _events.emit(ConnectingUiEvent.NavigateBack)
        }
    }

    private suspend fun attemptConnection() {
        var currentLog = ConnectionLog()

        try {
            // Run connection on Default dispatcher to keep Main thread free for animations
            val result = withContext(defaultDispatcher) {
                attemptReconnectionUseCase { progress ->
                    currentLog = currentLog.apply(progress)
                    // StateFlow is thread-safe, can update from any thread
                    _state.value = ConnectingState.Connecting(log = currentLog)
                }
            }

            when (result) {
                is ReconnectionResult.Success -> {
                    _events.emit(ConnectingUiEvent.NavigateToSessions)
                }
                is ReconnectionResult.Failure.DeviceNotFound -> {
                    // Device was unpaired from daemon - clear credentials locally
                    unpairDeviceUseCase(deviceId = null)
                    _events.emit(ConnectingUiEvent.DeviceUnpaired)
                }
                is ReconnectionResult.Failure -> {
                    _state.value = ConnectingState.Failed(
                        reason = result,
                        log = currentLog
                    )
                }
            }
        } catch (e: Exception) {
            _state.value = ConnectingState.Failed(
                reason = ReconnectionResult.Failure.Unknown(e.message ?: "Unknown error"),
                log = currentLog
            )
        }
    }
}
