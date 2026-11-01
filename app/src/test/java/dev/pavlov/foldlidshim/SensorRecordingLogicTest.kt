package dev.pavlov.foldlidshim

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SensorRecordingLogicTest {
    @Test fun selectsEligibleTypesAndPrefersFirstNonWakeUp() {
        val sensors = listOf(
            RecordingSensor("wake", 1, 0, "", true),
            RecordingSensor("normal", 1, 0, "", false),
            RecordingSensor("later", 1, 0, "", false),
            RecordingSensor("change", 2, 1, null, true),
            RecordingSensor("secret", 3, 0, "com.samsung.permission.SSENSOR", false),
            RecordingSensor("once", 4, 2, "", false),
            RecordingSensor("trigger", 5, 3, "", false),
            RecordingSensor("protected", 6, 1, "permission", false),
            RecordingSensor("public", 6, 1, "", true))
        assertEquals(listOf("normal", "change", "public"), SensorRecordingLogic.select(sensors))
    }

    @Test fun formatsEveryValueWithoutLocaleAndEscapesCsvFields() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("100;90;14;magnet;1.25;-2.0;3.0;4.0;5.0;6.0\n",
                SensorRecordingLogic.event(100, 90, 14, "magnet",
                    floatArrayOf(1.25f, -2f, 3f, 4f, 5f, 6f)))
            assertEquals("100;0;mark;\"a;\"\"b\"\"\"\n",
                SensorRecordingLogic.special(100, "mark", "a;\"b\""))
            assertEquals("100;0;header;123;SM-F966B\n", SensorRecordingLogic.header(100, 123, "SM-F966B"))
        } finally { Locale.setDefault(original) }
    }

    @Test fun timeLimitIncludesBoundaryAndSizeCountsUtf8BeforeWriting() {
        val defaults = RecordingBudget(100)
        assertEquals(30_000_000L, defaults.maxBytes)
        assertFalse(defaults.expired(90_000_000_099L))
        assertTrue(defaults.expired(90_000_000_100L))
        val timed = RecordingBudget(100, durationNs = 90)
        assertFalse(timed.expired(189))
        assertTrue(timed.expired(190))
        assertTrue(timed.accept(189, "ok"))
        assertFalse(timed.accept(190, "late"))
        val sized = RecordingBudget(0, maxBytes = 5)
        assertTrue(sized.accept(1, "я\n"))
        assertEquals(3L, sized.bytes)
        assertFalse(sized.accept(2, "abc"))
        assertEquals(3L, sized.bytes)
        assertTrue(sized.accept(3, "ab"))
        assertEquals(5L, sized.bytes)
        assertFalse(sized.accept(4, "\n"))
    }
}
