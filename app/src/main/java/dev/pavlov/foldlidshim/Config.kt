package dev.pavlov.foldlidshim

object Config {
    const val TAG = "FoldLidShim"

    const val DEFAULT_CLOSE_DEG = 10f
    const val DEFAULT_OPEN_DEG = 15f

    const val SOURCE_SAMSUNG = "samsung"
    const val SOURCE_ACCEL = "accel"
    const val SOURCE_HINGE = "hinge"
    const val DEFAULT_SOURCE = SOURCE_HINGE
    const val SAMSUNG_STRING_TYPE = "com.samsung.sensor.folding_angle"
    const val SAMSUNG_TYPE = 65686
    const val SUB_NAME = "Accelerometer-Sub"
}
