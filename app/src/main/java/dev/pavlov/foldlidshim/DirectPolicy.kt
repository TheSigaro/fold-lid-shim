package dev.pavlov.foldlidshim

object DirectPolicy {
    enum class Command { NONE, CLOSED, CANCEL }
    fun decide(desiredClosed: Boolean, currentState: Int?, ownRequestActive: Boolean): Command = when {
        !desiredClosed -> if (ownRequestActive) Command.CANCEL else Command.NONE
        ownRequestActive || currentState == 0 -> Command.NONE
        else -> Command.CLOSED
    }
}
