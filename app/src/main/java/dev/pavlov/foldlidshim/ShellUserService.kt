package dev.pavlov.foldlidshim

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku UserService process. Shizuku was started through adb, so the
 * process runs as uid shell and may call "cmd device_state". Must not depend on Application.
 */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun runDeviceState(arg: String): String {
        if (arg != Config.ARG_CLOSED && arg != Config.ARG_OPENED && arg != Config.ARG_RESET) {
            return "exit=-2\nunsupported argument"
        }
        return try {
            val process = ProcessBuilder("/system/bin/cmd", "device_state", "state", arg)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { output.append(it.readText()) }
                } catch (_: Exception) {
                }
            }
            reader.start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.join(1000)
                return "exit=-1\ntimeout\n$output"
            }
            reader.join(1000)
            "exit=${process.exitValue()}\n$output"
        } catch (e: Exception) {
            "exit=-3\n$e"
        }
    }
}
