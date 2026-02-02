package com.ras

import android.app.Application

/**
 * Test application for Robolectric tests.
 * Does not use Hilt to avoid WebRTC native library initialization issues.
 */
class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No Hilt, no WebRTC initialization
    }
}
