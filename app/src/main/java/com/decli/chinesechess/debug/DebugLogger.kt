package com.decli.chinesechess.debug

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val MAX_LINES = 400
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    fun log(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        synchronized(lock) {
            lines += line
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    fun export(context: Context): File {
        val directory = File(context.getExternalFilesDir("logs"), "sessions")
        directory.mkdirs()
        val file = File(directory, "xiangqi_log_${fileFormat.format(Date())}.txt")
        val content = buildString {
            appendLine("App: 老爸下象棋")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Time: ${Date()}")
            appendLine()
            synchronized(lock) {
                lines.forEach(::appendLine)
            }
        }
        file.writeText(content)
        log("EXPORT", "saved_log=${file.absolutePath}")
        return file
    }
}

