package com.orbisai.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AriaAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val store = BridgePairingStore(context)
        if (!store.bridgeEnabled) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeCommandService::class.java)
            )
        }.onFailure {
            store.lastStatus = "اتصال مستقیم نیاز به بازکردن دوباره برنامه دارد"
        }
    }
}
