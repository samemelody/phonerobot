package com.phonerobot.app.crash

import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash reporter (W6). Installs as the default UncaughtExceptionHandler;
 * on a crash writes a report into [crashDir] (app-private storage), keeps the
 * newest [maxFiles] reports, then chains to the previous handler so the system
 * crash dialog / process death still happen normally.
 *
 * No third-party SDK — reports live on the device until manually pulled:
 *   adb shell run-as com.phonerobot.app ls files/crash
 *   adb shell run-as com.phonerobot.app cat files/crash/<file> > crash.txt
 */
class CrashReporter(
    private val crashDir: File,
    private val appVersion: String,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) : Thread.UncaughtExceptionHandler {

    private val previousHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    /** Installs this handler as the global uncaught-exception handler. */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            crashDir.mkdirs()
            val fileName = "crash_${FILE_NAME_TS.format(Date())}.txt"
            File(crashDir, fileName).writeText(
                formatCrashReport(
                    threadName = thread.name,
                    throwable = throwable,
                    appVersion = appVersion,
                    device = deviceDescription(),
                    recentLog = readRecentLogcat(),
                )
            )
            trimOldFiles()
        } catch (_: Throwable) {
            // A crash handler must never throw; the chained handler below still runs.
        }
        previousHandler?.uncaughtException(thread, throwable)
    }

    /** Deletes the oldest reports so only the newest [maxFiles] remain. */
    fun trimOldFiles() {
        crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }                       // lexicographic == chronological here
            ?.dropLast(maxFiles)
            ?.forEach { it.delete() }
    }

    // ── Report formatting (pure, unit-tested) ───────────────────

    fun formatCrashReport(
        threadName: String,
        throwable: Throwable,
        appVersion: String,
        device: String,
        recentLog: String?,
    ): String = buildString {
        appendLine("PhoneRobot crash report")
        appendLine("Time: ${REPORT_TS.format(Date())}")
        appendLine("App version: $appVersion")
        appendLine("Device: $device")
        appendLine("Thread: $threadName")
        appendLine()
        appendLine("Stack trace:")
        appendLine(throwable.stackTraceToString())
        if (!recentLog.isNullOrBlank()) {
            appendLine()
            appendLine("Recent logcat (this process):")
            appendLine(recentLog)
        }
    }.toString()

    // ── Best-effort crash-context capture ───────────────────────

    private fun deviceDescription(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT})"

    /**
     * Tail of this process's own logcat. Since API 24 an app may read its own
     * logs without special permission. Best-effort: returns null on any failure.
     */
    private fun readRecentLogcat(): String? = try {
        val process = ProcessBuilder(
            "logcat", "-d", "--pid=${android.os.Process.myPid()}", "-t", "$LOGCAT_TAIL_LINES"
        ).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.takeLast(LOGCAT_MAX_CHARS)
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val DEFAULT_MAX_FILES = 5
        private const val LOGCAT_TAIL_LINES = 200
        private const val LOGCAT_MAX_CHARS = 64 * 1024
        private val FILE_NAME_TS = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        private val REPORT_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
