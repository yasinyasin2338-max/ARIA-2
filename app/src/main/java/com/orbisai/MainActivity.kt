package com.orbisai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.orbisai.bridge.AriaAccessibilityService
import com.orbisai.bridge.BridgeCommandService
import com.orbisai.bridge.BridgePairingStore
import com.orbisai.ui.OrbisApp
import com.orbisai.voice.ArisAlwaysOnVoiceService
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 2338
    }

    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRemoteBridgeNow()
        else Toast.makeText(this, "برای نمایش وضعیت ARIA Bridge اجازه اعلان لازم است", Toast.LENGTH_LONG).show()
    }
    private val alwaysVoiceMicRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAlwaysOnVoiceAfterMic()
        else Toast.makeText(this, "برای صدای همیشه‌فعال آریس، اجازه میکروفون لازم است", Toast.LENGTH_LONG).show()
    }
    private val alwaysVoiceNotificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAlwaysOnVoiceNow()
        else Toast.makeText(this, "برای نمایش وضعیت Voice همیشه‌فعال، اجازه اعلان لازم است", Toast.LENGTH_LONG).show()
    }

    private var shizukuRunning by mutableStateOf(false)
    private var shizukuGranted by mutableStateOf(false)
    private var shizukuUid by mutableStateOf<Int?>(null)
    private var restoredServices = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            shizukuRunning = false
            shizukuGranted = false
            shizukuUid = null
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            runOnUiThread {
                shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
                shizukuUid = if (shizukuGranted) runCatching { Shizuku.getUid() }.getOrNull() else null
                Toast.makeText(
                    this,
                    if (shizukuGranted) "مجوز Shizuku برای ARIA فعال شد" else "مجوز Shizuku داده نشد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshShizukuStatus()

        setContent {
            var showBridge by remember { mutableStateOf(false) }
            val bridgeStore = remember { BridgePairingStore(this@MainActivity) }

            Box {
                OrbisApp(onRequestMicrophone = { requestMicrophone() })
                Button(
                    onClick = {
                        refreshShizukuStatus()
                        showBridge = true
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    Text("ARIA Bridge")
                }
            }

            if (showBridge) {
                AlertDialog(
                    onDismissRequest = { showBridge = false },
                    title = { Text("ARIA Bridge + Shizuku") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                when {
                                    !shizukuRunning -> "Shizuku: اجرا نیست"
                                    shizukuGranted -> "Shizuku: متصل و مجاز${shizukuUid?.let { " (UID $it)" } ?: ""}"
                                    else -> "Shizuku: اجراست، ولی ARIA هنوز مجوز ندارد"
                                }
                            )

                            Button(onClick = { requestShizukuPermission() }) {
                                Text(if (shizukuGranted) "Shizuku متصل است" else "اجازه Shizuku به ARIA")
                            }

                            Text("شناسه امن این نصب: ${bridgeStore.deviceId}")
                            Text("وضعیت کانال: ${bridgeStore.lastStatus}")

                            Button(onClick = { startRemoteBridge() }) {
                                Text("روشن کردن اتصال مستقیم آریس")
                            }
                            TextButton(onClick = { stopRemoteBridge() }) {
                                Text("خاموش کردن اتصال مستقیم")
                            }
                            Button(onClick = { requestUnrestrictedBattery() }) {
                                Text("پایدار کردن اتصال در پس‌زمینه")
                            }

                            Button(onClick = { startAlwaysOnVoice() }) {
                                Text("فعال کردن «آریس» همیشه‌شنوا")
                            }
                            TextButton(onClick = { stopAlwaysOnVoice() }) {
                                Text("خاموش کردن Voice همیشه‌فعال")
                            }
                            Text("وقتی Voice همیشه‌فعال روشن باشد، یک اعلان دائمی می‌بینی. بگو «آریس» و بعد دستورت را بگو؛ صدا ذخیره نمی‌شود و متن فرمان برای پاسخ به ARIA ارسال می‌شود.")

                            Text("تست کنترل‌های محلی Accessibility")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { runLocalAccessibilityAction("BACK") }) { Text("Back") }
                                Button(onClick = { runLocalAccessibilityAction("HOME") }) { Text("Home") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { runLocalAccessibilityAction("RECENTS") }) { Text("Recents") }
                                Button(onClick = { runLocalAccessibilityAction("NOTIFICATIONS") }) { Text("اعلان‌ها") }
                            }

                            TextButton(onClick = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) {
                                Text("Accessibility")
                            }

                            TextButton(onClick = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }) {
                                Text("Notification Access")
                            }

                            Text("اتصال مستقیم فقط وقتی خودت آن را روشن کنی فعال می‌ماند. کنترل مجاز شامل Home، Back، Recents، Notifications، Tap، Swipe، Scroll، بازکردن برنامه، تایپ و خواندن عناصر قابل‌دسترسیِ غیررمزی است. رمزها، Secure Folder و داده‌های بانکی خوانده نمی‌شوند.")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBridge = false }) {
                            Text("بستن")
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuStatus()
        if (!restoredServices) {
            restoredServices = true
            restoreEnabledServices()
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun restoreEnabledServices() {
        val store = BridgePairingStore(this)
        if (store.bridgeEnabled) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, BridgeCommandService::class.java))
            }
        }
        if (
            store.voiceEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, ArisAlwaysOnVoiceService::class.java))
            }
        }
    }

    private fun refreshShizukuStatus() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (running) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        } else {
            false
        }
        val uid = if (running && granted) runCatching { Shizuku.getUid() }.getOrNull() else null

        runOnUiThread {
            shizukuRunning = running
            shizukuGranted = granted
            shizukuUid = uid
        }
    }

    private fun requestShizukuPermission() {
        refreshShizukuStatus()
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            Toast.makeText(this, "اول Shizuku را اجرا کن", Toast.LENGTH_SHORT).show()
            return
        }

        if (runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            Toast.makeText(this, "ARIA همین حالا مجوز Shizuku دارد", Toast.LENGTH_SHORT).show()
            refreshShizukuStatus()
            return
        }

        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            Toast.makeText(this, "مجوز قبلاً رد شده؛ از داخل Shizuku بخش Authorized applications را بررسی کن", Toast.LENGTH_LONG).show()
            return
        }

        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
            .onFailure {
                Toast.makeText(this, "درخواست مجوز Shizuku اجرا نشد", Toast.LENGTH_SHORT).show()
            }
    }

    private fun startRemoteBridge() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startRemoteBridgeNow()
    }

    private fun startRemoteBridgeNow() {
        BridgePairingStore(this).bridgeEnabled = true
        ContextCompat.startForegroundService(this, Intent(this, BridgeCommandService::class.java))
        Toast.makeText(this, "اتصال مستقیم ARIA روشن شد", Toast.LENGTH_SHORT).show()
    }

    private fun stopRemoteBridge() {
        BridgePairingStore(this).bridgeEnabled = false
        stopService(Intent(this, BridgeCommandService::class.java))
        Toast.makeText(this, "اتصال مستقیم ARIA خاموش شد", Toast.LENGTH_SHORT).show()
    }

    private fun requestUnrestrictedBattery() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "محدودیت باتری برای ARIA برداشته شده", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun startAlwaysOnVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            alwaysVoiceMicRequest.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startAlwaysOnVoiceAfterMic()
    }

    private fun startAlwaysOnVoiceAfterMic() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            alwaysVoiceNotificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startAlwaysOnVoiceNow()
    }

    private fun startAlwaysOnVoiceNow() {
        BridgePairingStore(this).voiceEnabled = true
        ContextCompat.startForegroundService(this, Intent(this, ArisAlwaysOnVoiceService::class.java))
        Toast.makeText(this, "Voice همیشه‌فعال آریس روشن شد؛ بگو «آریس»", Toast.LENGTH_LONG).show()
    }

    private fun stopAlwaysOnVoice() {
        BridgePairingStore(this).voiceEnabled = false
        stopService(Intent(this, ArisAlwaysOnVoiceService::class.java))
        Toast.makeText(this, "Voice همیشه‌فعال آریس خاموش شد", Toast.LENGTH_SHORT).show()
    }

    private fun runLocalAccessibilityAction(action: String) {
        val ok = AriaAccessibilityService.performApprovedAction(action)
        Toast.makeText(
            this,
            if (ok) "فرمان $action اجرا شد" else "Accessibility ARIA فعال نیست یا سرویس آماده نیست",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
