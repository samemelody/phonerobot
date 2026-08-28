package com.phonerobot.app

import android.app.Application
import android.util.Log
import com.phonerobot.app.ai.GemmaService
import com.phonerobot.app.crash.CrashReporter
import java.io.File

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
        crashReporter = CrashReporter(File(filesDir, "crash"), appVersion = versionName).also {
            it.install()
        }
        Log.d(TAG, "PhoneRobot app initialized")
    }

    private val versionName: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }

    companion object {
        private const val TAG = "PhoneRobotApp"
        lateinit var instance: PhoneRobotApplication
            private set
        lateinit var crashReporter: CrashReporter
            private set
    }
}
