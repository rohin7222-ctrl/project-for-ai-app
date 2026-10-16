package com.nextgen.agent

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches all uncaught exceptions across all threads, logs them to local storage,
 * and allows in-app inspection of crashes.
 */
object CrashHandler {
    private const val CRASH_FILE = "crash_log.txt"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    fun install(ctx: Context) {
        appContext = ctx.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(thread, throwable)
            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to save crash log", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun recordCrash(thread: Thread?, throwable: Throwable) {
        val ctx = appContext ?: return
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sb = StringBuilder()
            sb.append("=== CRASH AT $timestamp ===\n")
            sb.append("Thread: ${thread?.name ?: "Unknown"}\n")
            sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})\n")
            sb.append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
            sb.append("Stack Trace:\n$stackTrace\n")
            sb.append("----------------------------------------\n\n")

            val file = File(ctx.filesDir, CRASH_FILE)
            FileOutputStream(file, true).use { fos ->
                fos.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (ignored: Exception) {}
    }

    fun getCrashLogs(ctx: Context): String {
        return try {
            val file = File(ctx.filesDir, CRASH_FILE)
            if (file.exists()) file.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) {
            "Error reading crash logs: ${e.message}"
        }
    }

    fun clearCrashLogs(ctx: Context): Boolean {
        return try {
            val file = File(ctx.filesDir, CRASH_FILE)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            false
        }
    }

    fun hasCrashes(ctx: Context): Boolean {
        return try {
            val file = File(ctx.filesDir, CRASH_FILE)
            file.exists() && file.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
