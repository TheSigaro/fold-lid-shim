/*
 * Adapted from RikkaApps/Shizuku (Apache-2.0).
 * https://github.com/RikkaApps/Shizuku
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package dev.pavlov.foldlidshim.adb

import android.util.Log
import dev.pavlov.foldlidshim.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import dev.pavlov.foldlidshim.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import dev.pavlov.foldlidshim.adb.AdbProtocol.ADB_AUTH_TOKEN
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_AUTH
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_CLSE
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_CNXN
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_MAXDATA
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_OKAY
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_OPEN
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_STLS
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_STLS_VERSION
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_VERSION
import dev.pavlov.foldlidshim.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import javax.net.ssl.SSLSocket

private const val TAG = "FoldLidShim"

class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    internal fun remoteAddress() = java.net.InetSocketAddress(host, port)

    fun connect() {
        // Inside Socket.apply the name `port` would resolve to Socket.port (0 until connected).
        val remote = remoteAddress()
        socket = Socket().apply { connect(remote, 5000); soTimeout = 10000 }
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.command != A_AUTH || message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "shell:$command")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("not A_OKAY or A_CLSE")
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val message = AdbMessage.readFrom(inputStream)
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        if (::tlsSocket.isInitialized) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}