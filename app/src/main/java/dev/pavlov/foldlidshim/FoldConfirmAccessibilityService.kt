package dev.pavlov.foldlidshim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

class FoldConfirmAccessibilityService : AccessibilityService() {
    private var lastClickAt: Long? = null
    private var clickedDialog: Pair<Long, Int>? = null

    private fun allowed(): Boolean = ShimState.serviceRunning && Prefs.isEnabled(this) &&
        Prefs.getMode(this) == Prefs.MODE_DIRECT && ShimState.directConfirmationPending

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != SYSTEM_UI || !allowed()) return
        val generation = ShimState.directRequestGeneration
        val source = event.source ?: windows.firstOrNull { it.id == event.windowId }?.root ?: return
        // An event source may be a changed child. Ascend to its window root.
        var root = source
        while (true) {
            val parent = root.parent ?: break
            root = parent
        }
        if (root.packageName?.toString() != SYSTEM_UI) return
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
        val now = SystemClock.elapsedRealtime()
        val key = generation to root.windowId
        if (clickedDialog == key || lastClickAt?.let { now - it < 2000 } == true) return
        if (!allowed() || generation != ShimState.directRequestGeneration) return
        if (nodes[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            clickedDialog = key
            lastClickAt = now
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
