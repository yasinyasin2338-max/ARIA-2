package com.orbisai.bridge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Transparent Android accessibility bridge foundation.
 *
 * This service does not perform remote actions on its own. Android requires the
 * device owner to enable it explicitly in Accessibility settings.
 */
class AriaAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Foundation only: no hidden collection or automatic actions.
    }

    override fun onInterrupt() {
        // No-op.
    }
}
