package com.sepand.ai.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SepandAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** A bounded screen summary. Text from apps is treated as untrusted content by the model. */
    fun screenSnapshot(maxChars: Int = 9000): String {
        val root = rootInActiveWindow ?: return "No readable app window is active."
        val lines = LinkedHashSet<String>()
        collectScreenText(root, lines, 0)
        return buildString {
            append("Foreground package: ").append(root.packageName).append('\n')
            append(lines.joinToString("\n").take(maxChars))
        }.take(maxChars + 200)
    }

    private fun collectScreenText(node: AccessibilityNodeInfo, lines: MutableSet<String>, depth: Int) {
        if (depth > 22 || lines.size >= 180) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        val value = listOf(text, description, hint).filter { it.isNotBlank() }.distinct().joinToString(" | ")
        if (value.isNotBlank()) {
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isScrollable) add("scrollable")
            }
            lines += if (flags.isEmpty()) value else "$value [${flags.joinToString()}]"
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectScreenText(child, lines, depth + 1)
            child.recycleCompat()
            if (lines.size >= 180) break
        }
    }

    fun execute(action: String, value: String = ""): String = when (action) {
        "scroll_down" -> scroll(true)
        "scroll_up" -> scroll(false)
        "back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "Back action executed" else "Back action unavailable"
        "home" -> if (performGlobalAction(GLOBAL_ACTION_HOME)) "Home action executed" else "Home action unavailable"
        "click_text" -> clickText(value)
        "type_text" -> typeText(value)
        "read_screen" -> screenSnapshot()
        else -> "Unsupported accessibility action"
    }

    /** Send is always called only after the approval card is explicitly accepted by the user. */
    fun sendMessage(text: String, expectedPackage: String, expectedRecipient: String = ""): String {
        val root = rootInActiveWindow ?: return "Message not sent: no active app window."
        val actualPackage = root.packageName?.toString().orEmpty()
        if (expectedPackage.isBlank() || !actualPackage.equals(expectedPackage, ignoreCase = true)) {
            return "Message not sent: the foreground app is '$actualPackage', not the verified target '$expectedPackage'."
        }
        if (text.isBlank()) return "Message not sent: the message is empty."
        if (expectedRecipient.isBlank()) return "Message not sent: an exact recipient name is required."
        if (!hasExactVisibleLabel(root, expectedRecipient)) {
            return "Message not sent: I could not verify the exact recipient label '$expectedRecipient' on the current screen."
        }
        val typed = typeText(text)
        if (!typed.startsWith("Text entered")) return "Message not sent: $typed"
        val refreshedRoot = rootInActiveWindow ?: return "Message text was entered, but the app window disappeared. Nothing was sent."
        val refreshedPackage = refreshedRoot.packageName?.toString().orEmpty()
        if (!refreshedPackage.equals(expectedPackage, ignoreCase = true) || !hasExactVisibleLabel(refreshedRoot, expectedRecipient)) {
            return "Message text was entered, but the app or recipient changed before sending. Nothing was sent; review the message manually."
        }
        val sent = clickExactSendControl(refreshedRoot)
        return if (sent) "Send control activated in $actualPackage. Verify the conversation shows the sent message." else
            "Message text was entered, but no exact Send/ارسال control was found. Nothing was sent; review and send manually."
    }

    private fun scroll(down: Boolean): String {
        val root = rootInActiveWindow ?: return "No active app window"
        if (performScroll(root, down)) return if (down) "Scrolled down" else "Scrolled up"
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val start = if (down) dm.heightPixels * 0.78f else dm.heightPixels * 0.28f
        val end = if (down) dm.heightPixels * 0.28f else dm.heightPixels * 0.78f
        val path = Path().apply { moveTo(x, start); lineTo(x, end) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 420)).build()
        return if (dispatchGesture(gesture, null, null)) "Scroll gesture sent" else "Scroll failed"
    }

    private fun performScroll(node: AccessibilityNodeInfo, down: Boolean): Boolean {
        if (node.isScrollable) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val handled = performScroll(child, down)
            child.recycleCompat()
            if (handled) return true
        }
        return false
    }

    private fun clickText(text: String): String {
        val root = rootInActiveWindow ?: return "No active app window"
        val nodes = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrNull().orEmpty()
        val target = nodes.firstOrNull { it.isClickable && it.isEnabled } ?: nodes.firstOrNull { it.isEnabled }
            ?: return "No visible control matched: $text"
        val clickable = target.findClickableParent() ?: target
        return if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Clicked: $text" else "Click was rejected by the app"
    }

    private fun clickExactSendControl(root: AccessibilityNodeInfo): Boolean {
        val accepted = setOf("send", "send message", "ارسال", "فرستادن", "ارسال پیام", "send now")
        return findExactSendNode(root, accepted, 0)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findExactSendNode(node: AccessibilityNodeInfo, accepted: Set<String>, depth: Int): AccessibilityNodeInfo? {
        if (depth > 22) return null
        val labels = listOf(node.text?.toString(), node.contentDescription?.toString())
        val matches = labels.any { label -> label?.trim()?.lowercase()?.let(accepted::contains) == true }
        if (matches && node.isEnabled) {
            val clickable = node.findClickableParent()
            if (clickable?.isClickable == true) return clickable
            if (node.isClickable) return node
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val result = findExactSendNode(child, accepted, depth + 1)
            child.recycleCompat()
            if (result != null) return result
        }
        return null
    }

    private fun hasExactVisibleLabel(root: AccessibilityNodeInfo, expected: String): Boolean {
        val target = expected.trim().replace(Regex("\\s+"), " ")
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 24) return false
            val labels = listOf(node.text?.toString(), node.contentDescription?.toString())
            if (labels.any { it?.trim()?.replace(Regex("\\s+"), " ")?.equals(target, true) == true }) return true
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                val found = visit(child, depth + 1)
                child.recycleCompat()
                if (found) return true
            }
            return false
        }
        return visit(root, 0)
    }

    private fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "No active app window"
        val target = findFocusedEditable(root) ?: return "No focused text field. Tap the intended input field first."
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Text entered" else "The app rejected text entry"
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            child.recycleCompat()
            if (found != null) return found
        }
        return null
    }

    private fun AccessibilityNodeInfo.findClickableParent(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        repeat(7) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() { runCatching { recycle() } }

    companion object { @Volatile var instance: SepandAccessibilityService? = null }
}
