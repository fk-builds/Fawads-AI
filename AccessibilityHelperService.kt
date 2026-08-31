package com.fawads.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Helps Fawad's AI control other apps: open/close, go back, scroll,
 * click on a node and type into text fields.
 */
class AccessibilityHelperService : AccessibilityService() {

    companion object {
        var instance: AccessibilityHelperService? = null
            private set

        /** True when this accessibility service is enabled in system settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.contains(context.packageName) && it.contains(AccessibilityHelperService::class.java.simpleName) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun closeCurrentApp() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun scrollDown() = performGlobalAction(GLOBAL_ACTION_SCROLL_FORWARD)
    fun scrollUp() = performGlobalAction(GLOBAL_ACTION_SCROLL_BACKWARD)

    fun clickOnText(text: String) {
        findNode(text)?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    fun typeText(text: String) {
        val node = findNodeByClass("android.widget.EditText")
        if (node != null) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    private fun findNode(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeRecursive(root, text)
    }

    private fun findNodeByClass(className: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeRecursiveByClass(root, className)
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, true) == true) return node
        if (node.contentDescription?.toString()?.contains(text, true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, text)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeRecursiveByClass(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.toString() == className) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursiveByClass(child, className)
            if (found != null) return found
        }
        return null
    }
}
