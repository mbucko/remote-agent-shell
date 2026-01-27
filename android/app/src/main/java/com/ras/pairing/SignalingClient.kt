package com.ras.pairing

import com.ras.crypto.HmacUtils
import com.ras.crypto.toHex
import com.ras.proto.SignalError
import com.ras.proto.SignalRequest
import com.ras.proto.SignalResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

sealed class SignalingResult {
    data class Success(val sdpAnswer: String) : SignalingResult()
    data class Error(val code: SignalError.ErrorCode) : SignalingResult()
}

class SignalingClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private val PROTOBUF_MEDIA_TYPE = "application/x-protobuf".toMediaType()
    }

    /**
     * Send signaling request to daemon.
     */
    suspend fun sendSignal(
        ip: String,
        port: Int,
        sessionId: String,
        authKey: ByteArray,
        sdpOffer: String,
        deviceId: String,
        deviceName: String
    ): SignalingResult = withContext(Dispatchers.IO) {
        // Build request protobuf
        val signalRequest = SignalRequest.newBuilder()
            .setSdpOffer(sdpOffer)
            .setDeviceId(deviceId)
            .setDeviceName(deviceName)
            .build()

        val body = signalRequest.toByteArray()

        // Compute signature
        val timestamp = System.currentTimeMillis() / 1000
        val signature = HmacUtils.computeSignalingHmac(
            authKey,
            sessionId,
            timestamp,
            body
        )

        // Build HTTP request
        val request = Request.Builder()
            .url("http://$ip:$port/signal/$sessionId")
            .post(body.toRequestBody(PROTOBUF_MEDIA_TYPE))
            .header("X-RAS-Signature", signature.toHex())
            .header("X-RAS-Timestamp", timestamp.toString())
            .build()

        try {
            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.bytes()
                if (responseBody == null || responseBody.isEmpty()) {
                    return@withContext SignalingResult.Error(SignalError.ErrorCode.UNKNOWN)
                }

                val signalResponse = try {
                    SignalResponse.parseFrom(responseBody)
                } catch (e: Exception) {
                    return@withContext SignalingResult.Error(SignalError.ErrorCode.UNKNOWN)
                }

                // Validate response contains actual SDP
                if (signalResponse.sdpAnswer.isBlank()) {
                    return@withContext SignalingResult.Error(SignalError.ErrorCode.UNKNOWN)
                }

                SignalingResult.Success(signalResponse.sdpAnswer)
            } else {
                val errorBody = response.body?.bytes()
                if (errorBody != null) {
                    try {
                        val error = SignalError.parseFrom(errorBody)
                        SignalingResult.Error(error.code)
                    } catch (e: Exception) {
                        SignalingResult.Error(SignalError.ErrorCode.UNKNOWN)
                    }
                } else {
                    SignalingResult.Error(SignalError.ErrorCode.UNKNOWN)
                }
            }
        } catch (e: Exception) {
            SignalingResult.Error(SignalError.ErrorCode.UNKNOWN)
        }
    }
}
