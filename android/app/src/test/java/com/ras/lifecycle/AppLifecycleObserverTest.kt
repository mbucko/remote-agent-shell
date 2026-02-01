package com.ras.lifecycle

import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for AppLifecycleObserver.
 *
 * Verifies:
 * 1. Initial state is foreground (app starts in foreground)
 * 2. onStart sets appInForeground to true
 * 3. onStop sets appInForeground to false
 * 4. State transitions work correctly
 */
class AppLifecycleObserverTest {

    private lateinit var observer: AppLifecycleObserver
    private val mockOwner = mockk<LifecycleOwner>()

    @Before
    fun setup() {
        observer = AppLifecycleObserver()
    }

    @Test
    fun `initially app is in foreground`() {
        assertTrue(observer.appInForeground.value)
    }

    @Test
    fun `onStop sets appInForeground to false`() {
        observer.onStop(mockOwner)
        assertFalse(observer.appInForeground.value)
    }

    @Test
    fun `onStart sets appInForeground to true`() {
        observer.onStop(mockOwner)
        assertFalse(observer.appInForeground.value)

        observer.onStart(mockOwner)
        assertTrue(observer.appInForeground.value)
    }

    @Test
    fun `multiple onStop calls are idempotent`() {
        observer.onStop(mockOwner)
        observer.onStop(mockOwner)
        observer.onStop(mockOwner)
        assertFalse(observer.appInForeground.value)
    }

    @Test
    fun `multiple onStart calls are idempotent`() {
        observer.onStart(mockOwner)
        observer.onStart(mockOwner)
        observer.onStart(mockOwner)
        assertTrue(observer.appInForeground.value)
    }

    @Test
    fun `rapid foreground background transitions work correctly`() {
        // Simulate rapid transitions
        observer.onStop(mockOwner)
        assertFalse(observer.appInForeground.value)

        observer.onStart(mockOwner)
        assertTrue(observer.appInForeground.value)

        observer.onStop(mockOwner)
        assertFalse(observer.appInForeground.value)

        observer.onStart(mockOwner)
        assertTrue(observer.appInForeground.value)
    }
}
