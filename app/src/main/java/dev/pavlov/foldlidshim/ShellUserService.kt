package dev.pavlov.foldlidshim

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Runs in the Shizuku shell process; never depends on Application. */
class ShellUserService : IShellService.Stub() {
    override fun destroy() { exitProcess(0) }

    private fun runCommand(vararg command: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { output.append(it.readText()) }
            } catch (_: Exception) { }
        }
        reader.start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            -1 to "timeout"
        } else {
            reader.join(1000)
            if (reader.isAlive) -1 to "read timeout" else process.exitValue() to output.toString()
        }
    } catch (e: Exception) { -3 to e.toString() }

    override fun readDeviceState(): String {
        val (code, text) = runCommand("/system/bin/dumpsys", "device_state")
        return if (code == 0) text else ""
    }

    override fun readCurrentState(): Int {
        val (code, text) = runCommand("/system/bin/cmd", "device_state", "print-state")
        return if (code == 0) text.trim().toIntOrNull() ?: -1 else -1
    }

    override fun runDeviceState(arg: String): String {
        if (arg !in listOf(Config.ARG_CLOSED, Config.ARG_OPENED, Config.ARG_RESET)) {
            return "exit=-2\nunsupported argument"
        }
        val (code, text) = runCommand("/system/bin/cmd", "device_state", "state", arg)
        return "exit=$code\n$text"
    }
}
