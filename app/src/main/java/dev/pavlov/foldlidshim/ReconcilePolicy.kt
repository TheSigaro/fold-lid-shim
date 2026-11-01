package dev.pavlov.foldlidshim

enum class Command { NONE, RESET, CLOSED, OPENED }

object ReconcilePolicy {
    fun decide(desiredClosed: Boolean, info: DeviceStateInfo?, overrideActive: Boolean): Command {
        val matches = if (desiredClosed) info?.baseState == 0 else info?.baseState in listOf(2, 3)
        return if (matches) {
            if (overrideActive && info?.overrideActive == true) Command.RESET else Command.NONE
        } else if (desiredClosed) Command.CLOSED else Command.OPENED
    }
}
