package com.orbisai.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class BridgeCommandService : Service() {
    @Volatile private var running = false
    private lateinit var store: BridgePairingStore

    override fun onCreate() {
        super.onCreate()
        store = BridgePairingStore(this)
        createChannels()
        startForeground(
            2100,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ARIA Bridge")
                .setContentText("کانال فرمان امن فعال است؛ هر فرمان قبل از اجرا تأیید می‌خواهد.")
                .setOngoing(true)
                .build()
        )
        running = true
        thread(name = "aria-bridge-poller", isDaemon = true) { pollLoop() }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollLoop() {
        while (running) {
            try { pollOnce() } catch (_: Throwable) { }
            try { Thread.sleep(6000L) } catch (_: InterruptedException) { break }
        }
    }

    private fun pollOnce() {
        val url = URL("https://api.github.com/repos/yasinyasin2338-max/ARIA-2/issues/1/comments?per_page=100")
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
            val text = obj.optString("body", "")
            parseAndOffer(text, id)
        }
        if (maxSeen > store.lastCommentId) store.lastCommentId = maxSeen
    }

    private fun parseAndOffer(text: String, commentId: Long) {
        if (!text.startsWith("ARIA-CMD-V1\n")) return
        val fields = text.lineSequence().drop(1).mapNotNull {
            val p = it.indexOf('=')
            if (p <= 0) null else it.substring(0, p).trim() to it.substring(p + 1).trim()
        }.toMap()
        val device = fields["device"] ?: return
        if (device != store.deviceId) return
        val ts = fields["ts"]?.toLongOrNull() ?: return
        val nonce = fields["nonce"] ?: return
        val action = fields["action"]?.uppercase() ?: return
        val sig = fields["sig"] ?: return
        if (action !in setOf("HOME", "BACK", "RECENTS", "NOTIFICATIONS")) return
        if (!store.verify(ts, nonce, action, sig)) return
        store.lastStatus = "درخواست دریافت شد: $action"
        showApproval(action, commentId)
    }

    private fun showApproval(action: String, commentId: Long) {
        val approveIntent = Intent(this, BridgeActionReceiver::class.java).apply {
            this.action = BridgeActionReceiver.ACTION_APPROVE
            putExtra(BridgeActionReceiver.EXTRA_ACTION, action)
        }
        val rejectIntent = Intent(this, BridgeActionReceiver::class.java).apply {
            this.action = BridgeActionReceiver.ACTION_REJECT
            putExtra(BridgeActionReceiver.EXTRA_ACTION, action)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val approve = PendingIntent.getBroadcast(this, commentId.toInt(), approveIntent, flags)
        val reject = PendingIntent.getBroadcast(this, commentId.toInt() xor 0x5a5a, rejectIntent, flags)
        val notification = NotificationCompat.Builder(this, CHANNEL_COMMANDS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("درخواست ARIA Bridge")
            .setContentText("فرمان $action فقط با تأیید تو اجرا می‌شود.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "اجرا", approve)
            .addAction(0, "رد", reject)
            .build()
        getSystemService(NotificationManager::class.java).notify(commentId.toInt(), notification)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "ARIA Bridge status", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMMANDS, "ARIA Bridge approvals", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val CHANNEL_STATUS = "aria_bridge_status"
        private const val CHANNEL_COMMANDS = "aria_bridge_commands"
    }
}
