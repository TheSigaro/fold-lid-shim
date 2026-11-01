package dev.pavlov.foldlidshim

import dev.pavlov.foldlidshim.adb.AdbClient
import dev.pavlov.foldlidshim.adb.AdbKey
import dev.pavlov.foldlidshim.adb.AdbKeyStore
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdbClientTest {
    private class MemoryStore : AdbKeyStore {
        private var bytes: ByteArray? = null
        override fun put(bytes: ByteArray) { this.bytes = bytes }
        override fun get(): ByteArray? = bytes
    }

    @Test
    fun connectReachesTheGivenPortNotSocketPortZero() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = CountDownLatch(1)
            Thread { runCatching { server.accept().close() }; accepted.countDown() }.start()
            val client = AdbClient("127.0.0.1", server.localPort, AdbKey(MemoryStore(), "test"))
            runCatching { client.use { it.connect() } } // server closes at once, handshake fails
            assertTrue("server never saw the connection", accepted.await(5, TimeUnit.SECONDS))
        }
    }
}
