package dev.pavlov.foldlidshim

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbCommandsTest {
    @Test fun starterQuotesPaths() {
        assertEquals(
            "cp '/data/app/Shizuku libs/libshizuku.so' /data/local/tmp/shizuku_starter && " +
                "chmod 700 /data/local/tmp/shizuku_starter && " +
                "/data/local/tmp/shizuku_starter --apk='/data/app/Shizuku app/base.apk'",
            AdbCommands.starter("/data/app/Shizuku libs", "/data/app/Shizuku app/base.apk")
        )
    }

    @Test fun starterEscapesSingleQuotesInBothPaths() {
        assertEquals(
            "cp '/data/app/it'\\''s/lib/libshizuku.so' /data/local/tmp/shizuku_starter && " +
                "chmod 700 /data/local/tmp/shizuku_starter && " +
                "/data/local/tmp/shizuku_starter --apk='/data/app/it'\\''s/base.apk'",
            AdbCommands.starter("/data/app/it's/lib", "/data/app/it's/base.apk")
        )
    }

    @Test fun nudgeStartsOnlyBinderNudgeActivity() {
        assertEquals(
            "am start -n dev.pavlov.foldlidshim/.BinderNudgeActivity",
            AdbCommands.nudge("dev.pavlov.foldlidshim")
        )
    }

    @Test fun nudgeUsesSuppliedPackageName() {
        assertEquals(
            "am start -n dev.pavlov.foldlidshim.debug/.BinderNudgeActivity",
            AdbCommands.nudge("dev.pavlov.foldlidshim.debug")
        )
    }
}
