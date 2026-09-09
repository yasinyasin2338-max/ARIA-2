package com.orbisai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.orbisai.ui.OrbisApp

class MainActivity : ComponentActivity() {
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setContent { OrbisApp() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OrbisApp(onRequestMicrophone = { requestMicrophone() }) }
    }

    private fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
