package com.example.chibiwallpaper

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLogger {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                val dir = appContext.getExternalFilesDir(null)
                val file = File(dir, "last_crash.txt")
                file.writeText(sw.toString())
            } catch (_: Exception) {}
            default?.uncaughtException(thread, ex)
        }
    }
}
