package dev.pavlov.foldlidshim

data class DeviceStateInfo(val baseState: Int?, val overrideState: Int?, val overrideActive: Boolean) {
    companion object {
        fun parse(text: String): DeviceStateInfo {
            fun identifier(field: String): Int? = Regex("""(?m)^\s*$field=Optional\[DeviceState\{identifier=(\d+)\b""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
            return DeviceStateInfo(identifier("mBaseState"), identifier("mOverrideState"),
                Regex("""Override Request active:\s*true\b""").containsMatchIn(text))
        }
    }
}
