package com.orbisai.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Owner-enabled foreground remote bridge.
 *
 * The phone authenticates to ARIA with a per-install random secret kept only in
 * Android private storage. No shell, root, credential reading, Secure Folder,
 * banking data, or permission bypass is exposed.
 */
class BridgeCommandService : Service() {
    @Volatile private var running = false
    private lateinit var store: BridgePairingStore
    private var lastRegisterAt = 0L

    override fun onCreate() {
        super.onCreate()
        store = BridgePairingStore(this)
        createChannel()
        startForeground(2100, statusNotification("اتصال امن مستقیم در حال برقراری است…"))
        running = true
        thread(name = "aria-direct-bridge", isDaemon = true) { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        store.lastStatus = "اتصال مستقیم خاموش شد"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollLoop() {
        while (running) {
            try {
                if (System.currentTimeMillis() - lastRegisterAt > 60_000L) registerDevice()
                pollOnce()
            } catch (_: Throwable) {
                updateStatus("ارتباط موقتاً قطع شد؛ تلاش دوباره…")
            }
            try { Thread.sleep(1500L) } catch (_: InterruptedException) { break }
        }
    }

    private fun registerDevice(): Boolean = runCatching {
        val conn = connection("$BASE/v2/register", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val body = JSONObject()
            .put("device", store.deviceId)
            .put("token", store.pairingSecret)
            .toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (ok) {
            lastRegisterAt = System.currentTimeMillis()
            store.lastStatus = "اتصال مستقیم امن فعال است"
            updateStatus("متصل — کنترل مجاز ARIA فعال است")
        }
        ok
    }.getOrDefault(false)

    private fun pollOnce() {
        val conn = connection("$BASE/v2/poll/${store.deviceId}", "GET").apply {
            setRequestProperty("Authorization", "Bearer ${store.pairingSecret}")
        }
        when (val code = conn.responseCode) {
            204 -> { conn.disconnect(); return }
            401 -> {
                conn.disconnect()
                lastRegisterAt = 0L
                registerDevice()
                return
            }
            in 200..299 -> Unit
            else -> { conn.disconnect(); return }
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val cmd = JSONObject(body).optJSONObject("command") ?: return
        executeCommand(cmd)
    }

    private fun executeCommand(cmd: JSONObject) {
        val id = cmd.optString("id").take(80)
        val action = cmd.optString("action").uppercase()
        if (id.isBlank() || action.isBlank()) return
        store.lastStatus = "فرمان دریافت شد: $action"

        var detail = ""
        val ok = runCatching {
            when (action) {
                "PING" -> { detail = "pong"; true }
                "HOME", "BACK", "RECENTS", "NOTIFICATIONS" -> AriaAccessibilityService.performApprovedAction(action)
                "TAP" -> AriaAccessibilityService.tap(cmd.optInt("x"), cmd.optInt("y"))
                "SWIPE" -> AriaAccessibilityService.swipe(
                    cmd.optInt("x"), cmd.optInt("y"), cmd.optInt("x2"), cmd.optInt("y2"), cmd.optLong("duration", 450L)
                )
                "GET_UI" -> {
                    detail = AriaAccessibilityService.visibleUiSnapshot()
                    !detail.startsWith("ACCESSIBILITY_NOT_READY") && !detail.startsWith("NO_ACTIVE_WINDOW")
                }
                "TYPE_TEXT" -> {
                    val text = decodeText(cmd.optString("text64")) ?: return@runCatching false
                    AriaAccessibilityService.typeText(text)
                }
                "CLICK_TEXT" -> {
                    val text = decodeText(cmd.optString("text64")) ?: return@runCatching false
                    AriaAccessibilityService.clickText(text)
                }
                "SCROLL_FORWARD" -> AriaAccessibilityService.scroll(true)
                "SCROLL_BACKWARD" -> AriaAccessibilityService.scroll(false)
                "OPEN_APP" -> AriaAccessibilityService.openApp(cmd.optString("packageName"))
                else -> false
            }
        }.getOrDefault(false)

        if (detail.isBlank()) detail = if (ok) "ok" else "failed_or_accessibility_not_ready"
        postResult(id, action, ok, detail)
        store.lastStatus = if (ok) "اجرا شد: $action" else "اجرا نشد: $action"
        updateStatus(if (ok) "آخرین فرمان: $action ✓" else "آخرین فرمان: $action اجرا نشد")
    }

    private fun postResult(id: String, action: String, ok: Boolean, detail: String) {
        runCatching {
            val conn = connection("$BASE/v2/result/${store.deviceId}", "POST").apply {
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${store.pairingSecret}")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject()
                .put("id", id)
                .put("action", action)
                .put("ok", ok)
                .put("detail", detail.take(12000))
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        }
    }

    private fun decodeText(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        bytes.toString(Charsets.UTF_8).take(4000)
    }.getOrNull()

    private fun connection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("User-Agent", "ARIA-Android-Direct-Bridge/0.9")
        }

    private fun statusNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("ARIA Remote Bridge")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun updateStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(2100, statusNotification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "ARIA Remote Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_STATUS = "aria_remote_bridge_status"
        private const val BASE = "https://aria-server-new-production.up.railway.app/api/bridge"
    }
}
