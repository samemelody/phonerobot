package com.phonerobot.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for CrashReporter — report formatting and old-file trimming.
 * Pure JVM; the uncaughtException path (logcat exec, Build fields) is
 * device-side and not exercised here.
 */
class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun reporter(maxFiles: Int = 5) =
        CrashReporter(tmp.root, appVersion = "1.0.0", maxFiles = maxFiles)

    // ── Report formatting ───────────────────────────────────────

    @Test
    fun `report contains version device thread and stack trace`() {
        val report = reporter().formatCrashReport(
            threadName = "main",
            throwable = IllegalStateException("engine exploded"),
            appVersion = "1.2.3",
            device = "Honor TEST / Android 14 (API 34)",
            recentLog = null,
        )

        assertTrue(report.contains("PhoneRobot crash report"))
        assertTrue(report.contains("App version: 1.2.3"))
        assertTrue(report.contains("Device: Honor TEST / Android 14 (API 34)"))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("java.lang.IllegalStateException: engine exploded"))
    }

    @Test
    fun `report includes recent log section when provided`() {
        val report = reporter().formatCrashReport(
            threadName = "main",
            throwable = RuntimeException("boom"),
            appVersion = "1.0.0",
            device = "d",
            recentLog = "08-28 10:00:00.000 D/PhoneRobotApp: something happened",
        )
        assertTrue(report.contains("Recent logcat"))
        assertTrue(report.contains("something happened"))
    }

    @Test
    fun `report omits log section when log is null or blank`() {
        val withNull = reporter().formatCrashReport("main", RuntimeException("x"), "1.0.0", "d", null)
        val withBlank = reporter().formatCrashReport("main", RuntimeException("x"), "1.0.0", "d", "   ")
        assertFalse(withNull.contains("Recent logcat"))
        assertFalse(withBlank.contains("Recent logcat"))
    }

    @Test
    fun `nested exception causes appear in stack trace`() {
        val report = reporter().formatCrashReport(
            "worker",
            RuntimeException("outer", IllegalStateException("inner cause")),
            "1.0.0", "d", null,
        )
        assertTrue(report.contains("outer"))
        assertTrue(report.contains("Caused by: java.lang.IllegalStateException: inner cause"))
    }

    // ── Old-file trimming ───────────────────────────────────────

    private fun writeCrashFiles(count: Int): List<File> =
        (1..count).map { i ->
            // Name format must match production: crash_yyyy-MM-dd_HH-mm-ss.txt.
            // Fixed suffixes keep the sort order stable and chronological.
            tmp.newFile("crash_2026-08-28_00-00-%02d.txt".format(i))
        }

    @Test
    fun `trim keeps newest files and deletes older ones`() {
        val reporter = reporter(maxFiles = 3)
        val files = writeCrashFiles(5)

        reporter.trimOldFiles()

        val remaining = tmp.root.listFiles()!!.map { it.name }
        assertEquals(3, remaining.size)
        assertTrue(remaining.contains(files[2].name))
        assertTrue(remaining.contains(files[3].name))
        assertTrue(remaining.contains(files[4].name))
        assertFalse(remaining.contains(files[0].name))
        assertFalse(remaining.contains(files[1].name))
    }

    @Test
    fun `trim does nothing when under the limit`() {
        val reporter = reporter(maxFiles = 5)
        writeCrashFiles(2)

        reporter.trimOldFiles()

        assertEquals(2, tmp.root.listFiles()!!.size)
    }

    @Test
    fun `trim ignores non-crash files`() {
        val reporter = reporter(maxFiles = 1)
        tmp.newFile("other.txt")
        writeCrashFiles(3)

        reporter.trimOldFiles()

        val remaining = tmp.root.listFiles()!!.map { it.name }
        assertEquals(2, remaining.size)
        assertTrue(remaining.contains("other.txt"))
    }
}
