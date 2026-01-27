package com.ras.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    // Dependencies will be injected later
) : ViewModel() {

    private val _isConnected = MutableStateFlow(true)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _peerId = MutableStateFlow<String?>("laptop-abc123")
    val peerId: StateFlow<String?> = _peerId.asStateFlow()

    /**
     * Disconnect from the peer.
     * TODO: Implement properly with repository
     */
    fun disconnect() {
        viewModelScope.launch {
            // TODO: Call repository to disconnect
            _isConnected.value = false
            _peerId.value = null
        }
    }

    /**
     * Update ntfy server URL.
     */
    fun updateNtfyServer(server: String) {
        viewModelScope.launch {
            // TODO: Save to preferences
        }
    }

    /**
     * Update notification preferences.
     */
    fun updateNotificationPreferences(
        approvals: Boolean,
        completed: Boolean,
        errors: Boolean
    ) {
        viewModelScope.launch {
            // TODO: Save to preferences
        }
    }
}
