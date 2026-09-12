package com.orbisai.bridge

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BridgePairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("aria_bridge", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: randomHex(8).also {
            prefs.edit().putString("device_id", it).apply()
        }

    val pairingSecret: String
        get() = prefs.getString("pairing_secret", null) ?: randomSecret().also {
            prefs.edit().putString("pairing_secret", it).apply()
        }

    var lastCommentId: Long
        get() = prefs.getLong("last_comment_id", 0L)
        set(value) { prefs.edit().putLong("last_comment_id", value).apply() }

    var lastStatus: String
        get() = prefs.getString("last_status", "هنوز فرمانی دریافت نشده") ?: "هنوز فرمانی دریافت نشده"
        set(value) { prefs.edit().putString("last_status", value).apply() }

    fun verify(ts: Long, nonce: String, action: String, signature: String): Boolean {
        val now = System.currentTimeMillis() / 1000L
        if (kotlin.math.abs(now - ts) > 300L) return false
        val payload = "$deviceId|$ts|$nonce|$action"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return constantTimeEquals(expected, signature.lowercase())
    }

    private fun randomSecret(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
