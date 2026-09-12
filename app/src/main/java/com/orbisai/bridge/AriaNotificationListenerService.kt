package com.orbisai.bridge

import android.service.notification.NotificationListenerService

/**
 * Notification-access foundation. The user must explicitly enable notification
 * access in Android settings. This class does not transmit notification data.
 */
class AriaNotificationListenerService : NotificationListenerService()
