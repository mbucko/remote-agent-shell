package com.ras.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    // Dependencies will be injected in Phase 6d
) : ViewModel() {

    private val _sessionsState = MutableStateFlow<UiState<List<Session>>>(UiState.Loading)
    val sessionsState: StateFlow<UiState<List<Session>>> = _sessionsState.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Connected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    init {
        loadSessions()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            // TODO: Load from repository in Phase 6d
            // For now, show empty list
            _sessionsState.value = UiState.Success(emptyList())
        }
    }

    fun refreshSessions() {
        _sessionsState.value = UiState.Loading
        loadSessions()
    }
}
