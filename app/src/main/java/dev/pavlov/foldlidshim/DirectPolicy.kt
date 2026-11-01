package dev.pavlov.foldlidshim

object DirectPolicy {
    enum class Command { NONE, CLOSED, OPENED, CANCEL }

    /** ownRequestActive denotes our CLOSED request, including one awaiting confirmation. */
    fun decide(desiredClosed: Boolean, currentState: Int?, ownRequestActive: Boolean): Command = when {
        !desiredClosed && ownRequestActive -> Command.CANCEL
        desiredClosed && (ownRequestActive || currentState == 0) -> Command.NONE
        !desiredClosed && currentState in listOf(2, 3) -> Command.NONE
        desiredClosed -> Command.CLOSED
        else -> Command.OPENED
    }
}
