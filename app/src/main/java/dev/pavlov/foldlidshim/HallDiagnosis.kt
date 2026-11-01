package dev.pavlov.foldlidshim

/** Session history is based on base state, never on the overridden current state. */
class HallDiagnosis(private val startedAtMs: Long = 0) {
    enum class Result(val label: String, val action: String) {
        WORKING("работает", "Следуем Холлу, снимаем совпавший override"),
        STUCK_OPEN("залип открыто", "Закрываем по углу, раскрытие возвращаем Холлу"),
        STUCK_CLOSED("залип закрыто", "Угла нет - используйте кнопки или плитку Экран Fold"),
        UNKNOWN("пока неясно", "Наблюдаем угол и базовое состояние")
    }
    var maxAngle: Float? = null
        private set
    private var minAngle: Float? = null
    private var lastAngle: Float? = null
    private var previousBase: Int? = null
    private var baseChanged = false
    private var alwaysOpen = true
    private var alwaysClosed = true

    fun onAngle(angle: Float) {
        if (!angle.isFinite() || angle !in 0f..180f) return
        maxAngle = maxOf(maxAngle ?: angle, angle)
        minAngle = minOf(minAngle ?: angle, angle)
        lastAngle = angle
    }

    fun observe(base: Int?) {
        if (base == null) return
        if (previousBase != null && previousBase != base) baseChanged = true
        previousBase = base
        alwaysOpen = alwaysOpen && base in listOf(2, 3)
        alwaysClosed = alwaysClosed && base == 0
    }

    fun diagnose(nowMs: Long, close: Float = 10f, open: Float = 15f): Result {
        val angle = lastAngle
        val agrees = angle != null && ((previousBase == 0 && angle <= close) ||
            (previousBase in listOf(2, 3) && angle >= open))
        return when {
            baseChanged && agrees -> Result.WORKING
            alwaysOpen && previousBase != null && maxAngle != null && maxAngle!! > minAngle!! -> Result.STUCK_OPEN
            alwaysClosed && previousBase == 0 && maxAngle == 0f && nowMs - startedAtMs >= 60_000 -> Result.STUCK_CLOSED
            else -> Result.UNKNOWN
        }
    }
}
