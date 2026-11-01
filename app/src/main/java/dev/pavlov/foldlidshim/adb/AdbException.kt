/*
 * Adapted from RikkaApps/Shizuku (Apache-2.0).
 * https://github.com/RikkaApps/Shizuku
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package dev.pavlov.foldlidshim.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)
