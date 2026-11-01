package dev.pavlov.foldlidshim

object ConfirmButtonPicker {
    data class Node(val className: String, val text: String?, val viewId: String?, val clickable: Boolean)

    fun isRearDisplay(nodes: List<Node>): Boolean = nodes.any {
        it.className.contains("RearDisplayEducation") ||
            it.text?.trim() in listOf("Переключиться на другой экран?", "Switch screens?")
    }

    fun pick(nodes: List<Node>): Int? {
        if (!isRearDisplay(nodes)) return null
        return nodes.indexOfLast {
            val text = it.text?.trim().orEmpty()
            (it.className == "android.widget.Button" || it.clickable) && text.isNotEmpty() &&
                !text.equals("Отмена", ignoreCase = true) && !text.equals("Cancel", ignoreCase = true)
        }.takeIf { it >= 0 }
    }
}
