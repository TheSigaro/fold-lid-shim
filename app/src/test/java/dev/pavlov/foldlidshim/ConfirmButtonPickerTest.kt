package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class ConfirmButtonPickerTest {
    private fun tree(english: Boolean = false, buttonId: String? = "android:id/button1") = listOf(
        ConfirmButtonPicker.Node("android.widget.FrameLayout", null, null, false),
        ConfirmButtonPicker.Node("android.widget.ScrollView", null, null, false),
        ConfirmButtonPicker.Node("android.widget.TextView",
            if (english) "Switch to another screen?" else "Переключиться на другой экран?",
            "com.android.systemui:id/rear_display_title_text_view", false),
        ConfirmButtonPicker.Node("android.widget.TextView",
            if (english) "Turn your phone over and use the main camera to take higher resolution pictures."
            else "Переверните телефон и используйте основную камеру, чтобы делать снимки с более высоким разрешением.",
            null, false),
        ConfirmButtonPicker.Node("android.widget.ScrollView", null, "android:id/buttonPanel", false),
        ConfirmButtonPicker.Node("android.widget.Button", if (english) "Cancel" else "Отмена", "android:id/button2", true),
        ConfirmButtonPicker.Node("android.widget.Button", if (english) "Switch screens" else "Переключиться на другой экран", buttonId, true)
    )

    @Test fun realSamsungTree() { assertEquals(6, ConfirmButtonPicker.pick(tree())) }
    @Test fun englishTitleIdentifiedByViewId() { assertEquals(6, ConfirmButtonPicker.pick(tree(true))) }
    @Test fun fallbackWithoutButton1() { assertEquals(6, ConfirmButtonPicker.pick(tree(buttonId = null))) }
    @Test fun legacyTitleWithoutViewId() {
        val nodes = tree().toMutableList()
        nodes[2] = nodes[2].copy(viewId = null)
        assertEquals(6, ConfirmButtonPicker.pick(nodes))
    }
    @Test fun legacyEnglishWithoutIds() {
        val nodes = tree(true, buttonId = null).toMutableList()
        nodes[2] = nodes[2].copy(text = "Switch screens?", viewId = null)
        assertEquals(6, ConfirmButtonPicker.pick(nodes))
    }
    @Test fun foreignAlertWithButton1() {
        val nodes = tree().toMutableList()
        nodes[2] = nodes[2].copy(text = "Delete file?", viewId = null)
        assertNull(ConfirmButtonPicker.pick(nodes))
    }
    @Test fun onlyCancelAndEmptyButtons() {
        for (english in listOf(false, true)) {
            val nodes = tree(english).dropLast(1)
            assertNull(ConfirmButtonPicker.pick(nodes))
            assertNull(ConfirmButtonPicker.pick(nodes + ConfirmButtonPicker.Node("android.widget.Button", "", null, true)))
        }
    }
    @Test fun legacyWrapperClickableText() {
        assertEquals(1, ConfirmButtonPicker.pick(listOf(
            ConfirmButtonPicker.Node("com.android.systemui.reardisplay.RearDisplayEducationLottieViewWrapper", null, null, false),
            ConfirmButtonPicker.Node("android.widget.TextView", "Switch screens", null, true))))
    }
}
