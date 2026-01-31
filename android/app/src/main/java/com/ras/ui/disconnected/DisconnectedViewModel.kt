package com.ras.ui.disconnected

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.data.keystore.KeyManager
import com.ras.domain.startup.ClearCredentialsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Disconnected screen.
 * Handles reconnection and unpairing actions.
 */
@HiltViewModel
class DisconnectedViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val clearCredentialsUseCase: ClearCredentialsUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<DisconnectedState>(DisconnectedState.Idle)
    val state: StateFlow<DisconnectedState> = _state.asStateFlow()

    /**
     * Clear disconnected flag and navigate to startup to reconnect.
     */
    fun reconnect() {
        viewModelScope.launch {
            keyManager.setDisconnected(false)
            _state.value = DisconnectedState.NavigateToStartup
        }
    }

    /**
     * Clear all credentials and navigate to pairing screen.
     */
    fun unpair() {
        viewModelScope.launch {
            clearCredentialsUseCase()
            _state.value = DisconnectedState.NavigateToPairing
        }
    }
}

/**
 * UI state for the Disconnected screen.
 */
sealed class DisconnectedState {
    object Idle : DisconnectedState()
    object NavigateToStartup : DisconnectedState()
    object NavigateToPairing : DisconnectedState()
}
