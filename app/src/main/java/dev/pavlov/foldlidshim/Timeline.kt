package dev.pavlov.foldlidshim

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded chronological ring: discard oldest complete lines on wrap. No sensor-rate writes. */
object Timeline {
    private var file: File? = null
    private val io = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(64), ThreadPoolExecutor.DiscardPolicy())
    fun init(context: Context) { file = File(context.filesDir, "timeline.log") }
    fun event(message: String) {
        val line = "${System.currentTimeMillis()} elapsedNs=${SystemClock.elapsedRealtimeNanos()} ${message.replace('\n', ' ')}"
        Log.i(Config.TAG, line)
        val target = file ?: return
        io.execute {
            runCatching {
                synchronized(this) {
                    val bytes = (line.take(4000) + "\n").toByteArray()
                    if (target.length() + bytes.size > 65_536) {
                        val old = target.readBytes()
                        val start = old.indexOfFrom(10, (old.size - 32_768).coerceAtLeast(0))
                        target.writeBytes(if (start >= 0) old.copyOfRange(start + 1, old.size) else byteArrayOf())
                    }
                    target.appendBytes(bytes)
                }
            }
        }
    }
    private fun ByteArray.indexOfFrom(value: Int, start: Int): Int {
        for (i in start until size) if (this[i].toInt() == value) return i
        return -1
    }
    fun read(): String = synchronized(this) { runCatching { file?.readText().orEmpty() }.getOrDefault("") }
}
