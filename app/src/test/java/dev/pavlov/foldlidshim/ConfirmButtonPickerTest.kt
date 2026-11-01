package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class ConfirmButtonPickerTest {
    private fun node(text: String, button: Boolean = false, clickable: Boolean = button) =
        ConfirmButtonPicker.Node(if (button) "android.widget.Button" else "android.widget.TextView", text, null, clickable)

    @Test fun russianLabels() {
        assertEquals(2, ConfirmButtonPicker.pick(listOf(node("Переключиться на другой экран?"),
            node("Отмена", true), node("Переключиться на другой экран", true))))
    }

    @Test fun englishLabels() {
        assertEquals(2, ConfirmButtonPicker.pick(listOf(node("Switch screens?"),
            node("Cancel", true), node("Switch screens", true))))
    }

    @Test fun unrelatedDialog() {
        assertNull(ConfirmButtonPicker.pick(listOf(node("Delete file?"), node("Cancel", true), node("Delete", true))))
    }

    @Test fun onlyCancel() {
        for (cancel in listOf("Отмена", "Cancel")) {
            assertNull(ConfirmButtonPicker.pick(listOf(node("Switch screens?"), node(cancel, true))))
        }
    }

    @Test fun wrapperAndClickableText() {
        assertEquals(2, ConfirmButtonPicker.pick(listOf(
            ConfirmButtonPicker.Node("com.android.systemui.reardisplay.RearDisplayEducationLottieViewWrapper", null, "wrapper", false),
            node("Cancel", true), node("Switch screens", clickable = true))))
    }

    @Test fun ignoresEmptyButtonsAndNonClickableText() {
        assertNull(ConfirmButtonPicker.pick(listOf(node("Switch screens?"), node("", true), node("Explanation"))))
    }
}
