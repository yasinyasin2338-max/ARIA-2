package com.orbisai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import com.orbisai.ui.OrbisApp

class MainActivity : ComponentActivity() {
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var showBridge by remember { mutableStateOf(false) }
            Box {
                OrbisApp(onRequestMicrophone = { requestMicrophone() })
                Button(
                    onClick = { showBridge = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    Text("ARIA Bridge")
                }
            }
            if (showBridge) {
                AlertDialog(
                    onDismissRequest = { showBridge = false },
                    title = { Text("راه‌اندازی ARIA Bridge") },
                    text = { Text("این نسخه فقط مجوزهای رسمی Android را آماده می‌کند. هیچ مجوزی مخفیانه فعال نمی‌شود و هر دسترسی باید توسط خودت در تنظیمات روشن شود.") },
                    confirmButton = {
                        TextButton(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("Accessibility") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Notification Access") }
                    }
                )
            }
        }
    }

    private fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
