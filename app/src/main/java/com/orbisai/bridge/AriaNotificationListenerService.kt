package com.orbisai.bridge

import android.app.Notification
import android.service.notification.NotificationListenerService

/**
 * Owner-enabled notification access. Notification text is returned only on an
 * explicit bridge request, protected packages are excluded, and likely OTP/PIN
 * numbers are redacted before leaving the device.
 */
class AriaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: AriaNotificationListenerService? = null

        fun snapshot(): String {
            val service = instance ?: return "NOTIFICATION_ACCESS_NOT_READY"
            if (AriaSafetyPolicy.isLocked(service)) return "PROTECTED_LOCK_SCREEN"

            val out = StringBuilder()
            var count = 0
            for (sbn in service.activeNotifications.orEmpty().sortedByDescending { it.postTime }) {
                if (count >= 40 || out.length >= 11000) break
                val pkg = sbn.packageName.orEmpty()
                if (AriaSafetyPolicy.isProtectedPackage(service, pkg)) continue

                val extras = sbn.notification.extras ?: continue
                val title = AriaSafetyPolicy.sanitizeNotificationText(extras.getCharSequence(Notification.EXTRA_TITLE))
                val big = AriaSafetyPolicy.sanitizeNotificationText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                val text = if (big.isNotBlank()) big else
                    AriaSafetyPolicy.sanitizeNotificationText(extras.getCharSequence(Notification.EXTRA_TEXT))
                if (title.isBlank() && text.isBlank()) continue

                val label = runCatching {
                    @Suppress("DEPRECATION")
                    val info = service.packageManager.getApplicationInfo(pkg, 0)
                    service.packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(pkg)

                out.append(count).append('|')
                    .append("app=").append(clean(label).take(120)).append('|')
                    .append("package=").append(clean(pkg).take(180)).append('|')
                    .append("title=").append(clean(title).take(300)).append('|')
                    .append("text=").append(clean(text).take(500))
                    .append('\n')
                count++
            }
            return out.toString().ifBlank { "NO_SAFE_NOTIFICATIONS" }
        }

        private fun clean(value: String): String = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
