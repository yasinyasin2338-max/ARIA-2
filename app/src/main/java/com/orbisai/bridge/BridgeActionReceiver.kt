package com.orbisai.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BridgeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = BridgePairingStore(context)
        val action = intent.getStringExtra(EXTRA_ACTION)?.uppercase() ?: return
        when (intent.action) {
            ACTION_APPROVE -> {
                val ok = AriaAccessibilityService.performApprovedAction(action)
                store.lastStatus = if (ok) "اجرا شد: $action" else "Accessibility آماده نیست: $action"
            }
            ACTION_REJECT -> store.lastStatus = "رد شد: $action"
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.orbisai.bridge.APPROVE"
        const val ACTION_REJECT = "com.orbisai.bridge.REJECT"
        const val EXTRA_ACTION = "bridge_action"
    }
}
