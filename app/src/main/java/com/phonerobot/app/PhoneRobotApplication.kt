package com.phonerobot.app

import android.app.Application
import android.util.Log
import com.phonerobot.app.ai.GemmaService

/**
 * Application class for PhoneRobot.
 * Initializes global state and singletons.
 */
class PhoneRobotApplication : Application() {

    // Global singleton - AI model loaded only once
    val gemmaService: GemmaService by lazy { GemmaService(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "PhoneRobot app initialized")
    }

    companion object {
        private const val TAG = "PhoneRobotApp"
        lateinit var instance: PhoneRobotApplication
            private set
    }
}
