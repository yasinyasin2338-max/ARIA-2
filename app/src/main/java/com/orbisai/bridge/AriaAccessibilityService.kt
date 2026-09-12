package com.orbisai.bridge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Transparent Android accessibility bridge.
 *
 * It never performs actions on its own. Remote requests must be authenticated
 * and the device owner must explicitly approve each request from a notification.
 */
class AriaAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No screen scraping or hidden collection.
    }

    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var instance: AriaAccessibilityService? = null

        fun performApprovedAction(action: String): Boolean {
            val service = instance ?: return false
            val globalAction = when (action.uppercase()) {
                "HOME" -> GLOBAL_ACTION_HOME
                "BACK" -> GLOBAL_ACTION_BACK
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
            return service.performGlobalAction(globalAction)
        }
    }
}
