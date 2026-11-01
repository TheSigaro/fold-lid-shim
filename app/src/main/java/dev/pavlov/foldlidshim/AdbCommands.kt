package dev.pavlov.foldlidshim

/** Shell commands shared by autostart and plain JVM tests. */
object AdbCommands {
    fun starter(nativeLibraryDir: String, apkPath: String): String {
        val starter = "/data/local/tmp/shizuku_starter"
        return "cp ${quote("$nativeLibraryDir/libshizuku.so")} $starter && " +
            "chmod 700 $starter && $starter --apk=${quote(apkPath)}"
    }

    fun nudge(packageName: String): String = "am start -n $packageName/.BinderNudgeActivity"

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
