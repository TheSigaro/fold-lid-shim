package dev.pavlov.foldlidshim

internal data class RecordingSensor<T>(
    val sensor: T, val type: Int, val mode: Int, val permission: String?, val wakeUp: Boolean
)

internal object SensorRecordingLogic {
    fun <T> select(sensors: List<RecordingSensor<T>>): List<T> = sensors
        .filter { it.permission.isNullOrEmpty() && it.mode in 0..1 }
        .groupBy { it.type }.values.map { group ->
            (group.firstOrNull { !it.wakeUp } ?: group.first()).sensor
        }

    fun field(value: String): String = if (value.any { it in ";\"\r\n" })
        "\"${value.replace("\"", "\"\"")}\"" else value

    fun event(received: Long, timestamp: Long, type: Int, name: String, values: FloatArray): String =
        "$received;$timestamp;$type;${field(name)}" + values.joinToString("") { ";$it" } + "\n"

    fun special(received: Long, kind: String, text: String): String =
        "$received;0;$kind;${field(text)}\n"

    fun header(received: Long, unixMs: Long, model: String): String =
        "$received;0;header;$unixMs;${field(model)}\n"
}

internal class RecordingBudget(
    val startNs: Long, val durationNs: Long = 90_000_000_000L, val maxBytes: Long = 30_000_000L
) {
    var bytes = 0L
        private set
    fun expired(nowNs: Long): Boolean = nowNs - startNs >= durationNs
    fun accept(nowNs: Long, line: String): Boolean {
        val size = line.toByteArray(Charsets.UTF_8).size
        if (expired(nowNs) || bytes + size > maxBytes) return false
        bytes += size
        return true
    }
}
