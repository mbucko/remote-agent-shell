package com.ras.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ras.pairing.PairingManager
import com.ras.pairing.PairingState
import com.ras.pairing.ParsedQrPayload
import com.ras.pairing.QrParseResult
import com.ras.pairing.QrPayloadParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingManager: PairingManager
) : ViewModel() {

    val state: StateFlow<PairingState> = pairingManager.state

    private val _qrParseError = MutableStateFlow<QrParseResult.ErrorCode?>(null)
    val qrParseError: StateFlow<QrParseResult.ErrorCode?> = _qrParseError.asStateFlow()

    init {
        // Start in scanning mode
        pairingManager.startScanning()
    }

    /**
     * Called when a QR code is scanned.
     */
    fun onQrScanned(qrContent: String) {
        _qrParseError.value = null

        when (val result = QrPayloadParser.parse(qrContent)) {
            is QrParseResult.Success -> {
                pairingManager.startPairing(result.payload)
            }
            is QrParseResult.Error -> {
                _qrParseError.value = result.code
            }
        }
    }

    /**
     * Retry pairing from the beginning.
     */
    fun retry() {
        _qrParseError.value = null
        pairingManager.reset()
        pairingManager.startScanning()
    }

    /**
     * Clear QR parse error.
     */
    fun clearQrError() {
        _qrParseError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        pairingManager.reset()
    }
}
