package com.carter.glasslauncher.data

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Fire OS keeps input names in a signature-only provider, outside Android's custom labels.
 * The existing home accessibility helper can read the same labels on the native Inputs
 * screen. Capture only those known input-card fields, including after a rename returns
 * to that screen; no navigation or other screen content is collected.
 */
object HdmiLabelSync {
    const val INPUT_PACKAGE = "com.amazon.tv.inputpreference.service"
    private const val PREFS = "hdmi_tv_labels"
    private const val PRIMARY = "$INPUT_PACKAGE:id/inputTabItemPrimaryName"
    private const val SECONDARY = "$INPUT_PACKAGE:id/inputTabItemSecondaryName"
    private val portPattern = Regex("^HDMI\\s*([1-3])$", RegexOption.IGNORE_CASE)

    fun name(context: Context, port: Int): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("port_$port", null)

    fun capture(context: Context, root: AccessibilityNodeInfo?) {
        if (root?.packageName?.toString() != INPUT_PACKAGE) return
        val names = mutableMapOf<Int, String>()
        fun scan(node: AccessibilityNodeInfo) {
            if (node.viewIdResourceName == PRIMARY) {
                val primary = node.text?.toString()?.trim().orEmpty()
                var parent = node.parent
                var foundPort: Int? = null
                // Stay inside one card. Never match another input elsewhere in the list.
                repeat(3) {
                    val candidate = parent
                    if (candidate != null && foundPort == null) {
                        val primaries = candidate.findAccessibilityNodeInfosByViewId(PRIMARY)
                        if (primaries.size == 1) {
                            val secondaries = candidate.findAccessibilityNodeInfosByViewId(SECONDARY)
                            foundPort = secondaries.firstNotNullOfOrNull { secondary ->
                                portPattern.matchEntire(secondary.text?.toString().orEmpty().trim())
                                    ?.groupValues?.get(1)?.toIntOrNull()
                            }
                        }
                        parent = candidate.parent
                    }
                }
                val port = foundPort ?: portPattern.matchEntire(primary)?.groupValues?.get(1)?.toIntOrNull()
                if (port != null && primary.isNotBlank()) {
                    names[port] = if (portPattern.matches(primary)) "HDMI $port" else primary
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { scan(it) }
        }
        scan(root)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changed = names.filter { (port, label) -> prefs.getString("port_$port", null) != label }
        if (changed.isNotEmpty()) {
            prefs.edit().apply { changed.forEach { (port, label) -> putString("port_$port", label) } }.apply()
            Log.i("HdmiLabels", "Updated TV input labels: $changed")
        }
    }
}
