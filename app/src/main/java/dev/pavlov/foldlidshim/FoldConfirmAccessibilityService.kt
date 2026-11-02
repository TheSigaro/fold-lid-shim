package dev.pavlov.foldlidshim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

class FoldConfirmAccessibilityService : AccessibilityService() {
    private var clickedDialog: Pair<Long, Int>? = null

    private fun allowed(): Boolean = ShimState.serviceRunning && Prefs.isEnabled(this) &&
        ShimState.directConfirmationPending

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!allowed()) return
        if (event?.packageName?.toString() != SYSTEM_UI) return
        val generation = ShimState.directRequestGeneration
        val source = event.source ?: rootInActiveWindow ?: return
        // An event source may be a changed child. Ascend to its window root.
        var root = source
        while (true) {
            val parent = root.parent ?: break
            root = parent
        }
        if (root.packageName?.toString() != SYSTEM_UI) return
        val title = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/rear_display_title_text_view")
            .firstOrNull { it.packageName?.toString() == SYSTEM_UI }
        val button = if (title != null) root.findAccessibilityNodeInfosByViewId("android:id/button1")
            .firstOrNull { it.packageName?.toString() == SYSTEM_UI } else null
        if (button != null) {
            click(button, generation, root.windowId)
            return
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            // Never inspect embedded content belonging to a different package.
            if (node.packageName?.toString() != SYSTEM_UI) return
            nodes.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }
        }
        visit(root)
        val descriptions = nodes.map {
            ConfirmButtonPicker.Node(it.className?.toString().orEmpty(), it.text?.toString(),
                it.viewIdResourceName, it.isClickable)
        }
        if (!ConfirmButtonPicker.isRearDisplay(descriptions)) return
        if (!loggedDialog) {
            loggedDialog = true
            Log.i(Config.TAG, "Rear display dialog: " + descriptions.joinToString("\n") {
                "className=${it.className} viewIdResourceName=${it.viewId} text=${it.text}"
            })
        }
        val index = ConfirmButtonPicker.pick(descriptions) ?: return
        click(nodes[index], generation, root.windowId)
    }

    private fun click(button: AccessibilityNodeInfo, generation: Long, windowId: Int) {
        val key = generation to windowId
        if (clickedDialog == key) return
        if (!allowed() || generation != ShimState.directRequestGeneration) return
        Timeline.event("rear display dialog seen generation=$generation window=$windowId")
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            clickedDialog = key
            Timeline.event("rear display confirm click generation=$generation")
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private var loggedDialog = false
        private const val SYSTEM_UI = "com.android.systemui"

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val component = ComponentName(context, FoldConfirmAccessibilityService::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == component
            }
        }
    }
}
