package com.ras.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.connection.ConnectionLog
import com.ras.domain.startup.AttemptReconnectionUseCase
import com.ras.domain.startup.CheckCredentialsUseCase
import com.ras.domain.startup.ClearCredentialsUseCase
import com.ras.domain.startup.CredentialStatus
import com.ras.domain.startup.ReconnectionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the startup screen.
 * Orchestrates the app startup flow:
 * 1. Check for stored credentials
 * 2. If credentials exist, attempt reconnection
 * 3. Navigate to appropriate screen based on result
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val checkCredentialsUseCase: CheckCredentialsUseCase,
    private val attemptReconnectionUseCase: AttemptReconnectionUseCase,
    private val clearCredentialsUseCase: ClearCredentialsUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        checkCredentialsAndConnect()
    }

    private fun checkCredentialsAndConnect() {
        viewModelScope.launch {
            try {
                when (checkCredentialsUseCase()) {
                    CredentialStatus.NoCredentials -> {
                        _state.value = StartupState.NavigateToPairing
                    }
                    is CredentialStatus.HasCredentials -> {
                        _state.value = StartupState.Connecting()
                        attemptReconnection()
                    }
                }
            } catch (e: Exception) {
                // If credential check fails, navigate to pairing
                _state.value = StartupState.NavigateToPairing
            }
        }
    }

    private suspend fun attemptReconnection() {
        var currentLog = ConnectionLog()

        try {
            // Run connection on Default dispatcher to keep Main thread free for animations
            val result = withContext(Dispatchers.Default) {
                attemptReconnectionUseCase { progress ->
                    currentLog = currentLog.apply(progress)
                    // StateFlow is thread-safe, can update from any thread
                    _state.value = StartupState.Connecting(log = currentLog)
                }
            }
            when (result) {
                is ReconnectionResult.Success -> {
                    _state.value = StartupState.NavigateToSessions
                }
                is ReconnectionResult.Failure -> {
                    _state.value = StartupState.ConnectionFailed(
                        reason = result,
                        log = currentLog
                    )
                }
            }
        } catch (e: Exception) {
            _state.value = StartupState.ConnectionFailed(
                reason = ReconnectionResult.Failure.Unknown(e.message ?: "Unknown error"),
                log = currentLog
            )
        }
    }

    /**
     * Retry reconnection after a failure.
     */
    fun retry() {
        viewModelScope.launch {
            _state.value = StartupState.Connecting()
            attemptReconnection()
        }
    }

    /**
     * Clear credentials and navigate to pairing screen.
     * Used when user wants to re-pair with daemon.
     */
    fun rePair() {
        viewModelScope.launch {
            clearCredentialsUseCase()
            _state.value = StartupState.NavigateToPairing
        }
    }
}
