package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class DeviceStateInfoTest {
    @Test fun parsesRealDump() {
        val info = DeviceStateInfo.parse("""
            mBaseState=Optional[DeviceState{identifier=0, name='CLOSED', app_accessible=true}]
            mOverrideState=Optional[DeviceState{identifier=3, name='OPENED', app_accessible=true}]
            Override Request active: true
        """.trimIndent())
        assertEquals(DeviceStateInfo(0, 3, true), info)
    }
    @Test fun parsesEmptyOverrideAndEveryBase() {
        for (base in 0..5) {
            assertEquals(DeviceStateInfo(base, null, false), DeviceStateInfo.parse(
                "mBaseState=Optional[DeviceState{identifier=$base, name='OPENED'}]\r\n" +
                    "mOverrideState=Optional.empty\r\nOverride Request active: false"))
        }
    }
    @Test fun emptyAndGarbageAreUnknown() {
        for (text in listOf("", "garbage", "mBaseState=Optional.empty", "mBaseState=Optional[DeviceState{identifier=oops}]"))
            assertEquals(DeviceStateInfo(null, null, false), DeviceStateInfo.parse(text))
    }
}
