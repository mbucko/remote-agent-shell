package com.ras.util

/**
 * Generic UI state wrapper for loading, success, and error states.
 */
sealed class UiState<out T> {
    /** Initial loading state */
    data object Loading : UiState<Nothing>()

    /** Successful state with data */
    data class Success<T>(val data: T) : UiState<T>()

    /** Error state with message and optional retry action */
    data class Error(
        val message: String,
        val retry: (() -> Unit)? = null
    ) : UiState<Nothing>()
}

/**
 * Map the data in a Success state.
 */
inline fun <T, R> UiState<T>.map(transform: (T) -> R): UiState<R> {
    return when (this) {
        is UiState.Loading -> UiState.Loading
        is UiState.Success -> UiState.Success(transform(data))
        is UiState.Error -> UiState.Error(message, retry)
    }
}

/**
 * Get the data or null if not in Success state.
 */
fun <T> UiState<T>.getOrNull(): T? {
    return when (this) {
        is UiState.Success -> data
        else -> null
    }
}

/**
 * Get the data or a default value if not in Success state.
 */
fun <T> UiState<T>.getOrDefault(default: T): T {
    return when (this) {
        is UiState.Success -> data
        else -> default
    }
}
