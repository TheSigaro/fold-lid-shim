package dev.pavlov.foldlidshim

import dev.pavlov.foldlidshim.adb.AdbMessage
import dev.pavlov.foldlidshim.adb.AdbProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbMessageTest {
    private fun parse(bytes: ByteArray) = AdbMessage.readFrom(DataInputStream(ByteArrayInputStream(bytes)))
    @Test fun cnxnUsesLittleEndianHeaderAndNullTerminatedBanner() {
        val message = AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::")
        val bytes = message.toByteArray()
        assertArrayEquals("CNXN".toByteArray(), bytes.copyOfRange(0, 4))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.A_VERSION, header.getInt(4))
        assertEquals(4096, header.getInt(8))
        assertEquals(7, header.getInt(12))
        assertEquals("host::\u0000", String(bytes.copyOfRange(24, bytes.size)))
        assertEquals(message, parse(bytes))
    }
    @Test fun openAndBinaryWrteRoundTripAndKeepStreamBoundaries() {
        val open = AdbMessage(AdbProtocol.A_OPEN, 1, 0, "shell:id")
        val wrte = AdbMessage(AdbProtocol.A_WRTE, 17, 1, byteArrayOf(0, -1, 127, -128))
        val input = DataInputStream(ByteArrayInputStream(open.toByteArray() + wrte.toByteArray()))
        assertEquals(open, AdbMessage.readFrom(input))
        assertEquals(wrte, AdbMessage.readFrom(input))
        assertEquals(0, input.available())
        assertEquals(510, wrte.data_crc32)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsChecksumCorruption() {
        val bytes = AdbMessage(AdbProtocol.A_WRTE, 2, 1, byteArrayOf(1)).toByteArray()
        bytes[24] = 2
        parse(bytes)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadMagic() {
        val bytes = AdbMessage(AdbProtocol.A_CNXN, 0, 0, "host::").toByteArray()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        parse(bytes)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizeBeforeAllocatingPayload() {
        val bytes = AdbMessage(AdbProtocol.A_WRTE, 2, 1, byteArrayOf()).toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE)
        parse(bytes)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLength() {
        val bytes = AdbMessage(AdbProtocol.A_WRTE, 2, 1, byteArrayOf()).toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1)
        parse(bytes)
    }
    @Test(expected = EOFException::class)
    fun rejectsTruncatedPayload() {
        val bytes = AdbMessage(AdbProtocol.A_OPEN, 1, 0, "shell:id").toByteArray()
        parse(bytes.copyOf(bytes.size - 1))
    }
}
