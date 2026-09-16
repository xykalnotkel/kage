package dev.kage.manager.core

import android.util.Log as AndroidLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small app logger: logcat plus a ring buffer the UI can show. */
object Log {

    private const val TAG = "Kage"
    private const val MAX_LINES = 500
    private val buffer = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var verbose = true

    @Synchronized
    private fun push(level: String, tag: String, message: String) {
        val line = "${format.format(Date())} $level/$tag: $message"
        AndroidLog.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, TAG, line)
        if (!verbose && level != "E") return
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
    }

    fun d(tag: String, message: String) = push("D", tag, message)
    fun i(tag: String, message: String) = push("I", tag, message)
    fun w(tag: String, message: String) = push("W", tag, message)
    fun e(tag: String, message: String) = push("E", tag, message)
    fun e(tag: String, t: Throwable) = push("E", tag, t.toString())

    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()
}
