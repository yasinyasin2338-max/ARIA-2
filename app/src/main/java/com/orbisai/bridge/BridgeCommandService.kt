package com.orbisai.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * User-started foreground bridge for a small allowlist of Android navigation actions.
 *
 * Commands are read from one public GitHub issue, but are accepted only when GitHub
 * reports that the comment author is the owner's exact account, the command targets
 * this installation's random device id, and the timestamp is fresh. No arbitrary shell,
 * credential access, screen capture, hidden collection, or unrestricted remote code is used.
 */
class BridgeCommandService : Service() {
    @Volatile private var running = false
    private lateinit var store: BridgePairingStore

    override fun onCreate() {
        super.onCreate()
        store = BridgePairingStore(this)
        createChannel()
        startForeground(
            2100,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ARIA Remote Bridge")
                .setContentText("اتصال از راه دور فعال است — فقط فرمان‌های محدود و مجاز ARIA")
                .setOngoing(true)
                .build()
        )
        running = true
        thread(name = "aria-bridge-poller", isDaemon = true) {
            sendBeacon("register", store.deviceId, "ready")
            pollLoop()
        }
    }

    override fun onDestroy() {
        running = false
        sendBeacon("status", store.deviceId, "stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollLoop() {
        while (running) {
            try { pollOnce() } catch (_: Throwable) { }
            try { Thread.sleep(4000L) } catch (_: InterruptedException) { break }
        }
    }

    private fun pollOnce() {
        val url = URL(COMMENTS_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ARIA-Android-Bridge")
        }
        if (conn.responseCode !in 200..299) return
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        var maxSeen = store.lastCommentId
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optLong("id", 0L)
            if (id <= store.lastCommentId) continue
            maxSeen = maxOf(maxSeen, id)

            val author = obj.optJSONObject("user")?.optString("login", "") ?: ""
            if (author != TRUSTED_GITHUB_LOGIN) continue

            val text = obj.optString("body", "")
            parseAndExecute(text)
        }
        if (maxSeen > store.lastCommentId) store.lastCommentId = maxSeen
    }

    private fun parseAndExecute(text: String) {
        if (!text.startsWith("ARIA-CMD-V2\n")) return
        val fields = text.lineSequence().drop(1).mapNotNull {
            val p = it.indexOf('=')
            if (p <= 0) null else it.substring(0, p).trim() to it.substring(p + 1).trim()
        }.toMap()

        val device = fields["device"] ?: return
        if (device != store.deviceId) return

        val ts = fields["ts"]?.toLongOrNull() ?: return
        val now = System.currentTimeMillis() / 1000L
        if (abs(now - ts) > 300L) return

        val nonce = fields["nonce"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,64}")) } ?: return
        val action = fields["action"]?.uppercase() ?: return
        if (action !in ALLOWED_ACTIONS) return

        store.lastStatus = "فرمان دریافت شد: $action"
        val ok = AriaAccessibilityService.performApprovedAction(action)
        store.lastStatus = if (ok) "اجرا شد: $action" else "Accessibility آماده نیست: $action"
        sendBeacon("result", store.deviceId, "$nonce/${if (ok) "ok" else "fail"}/$action")
        updateStatusNotification(if (ok) "آخرین فرمان: $action ✓" else "فرمان $action اجرا نشد")
    }

    private fun sendBeacon(kind: String, deviceId: String, detail: String) {
        try {
            val safeKind = kind.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val safeDevice = deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val safeDetail = detail.replace(Regex("[^A-Za-z0-9_/-]"), "_")
            val url = URL("$BEACON_BASE/$safeKind/$safeDevice/$safeDetail")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ARIA-Android-Bridge")
            }
            runCatching { conn.responseCode }
            conn.disconnect()
        } catch (_: Throwable) {
            // Beacons are only status reporting. Command execution does not depend on them.
        }
    }

    private fun updateStatusNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ARIA Remote Bridge")
            .setContentText(text)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2100, n)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "ARIA Remote Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_STATUS = "aria_remote_bridge_status"
        private const val TRUSTED_GITHUB_LOGIN = "yasinyasin2338-max"
        private const val COMMENTS_URL = "https://api.github.com/repos/yasinyasin2338-max/ARIA-2/issues/1/comments?per_page=100"
        private const val BEACON_BASE = "https://aria-server-new-production.up.railway.app/api/bridge/beacon"
        private val ALLOWED_ACTIONS = setOf("HOME", "BACK", "RECENTS", "NOTIFICATIONS")
    }
}
