package com.eagalts.still

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Ловит необработанные исключения и сохраняет стек; при следующем запуске Still показывает его с «Скопировать»
 * (из Plume). В тексте — только версии и стек: ни модели телефона, ни путей, ни имён.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                val header = "Still ${info.versionName} (${info.longVersionCode}), Android ${Build.VERSION.SDK_INT}, ${thread.name}\n\n"
                File(app.filesDir, FILE).writeText(header + scrub(sw.toString()))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Пути к файлам и адреса content:// из сообщений исключений — вырезаются. */
    internal fun scrub(s: String): String = s
        .replace(Regex("content://\\S+"), "content://…")
        .replace(Regex("/(storage|sdcard|data)/\\S+"), "/…")

    fun takeLast(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
