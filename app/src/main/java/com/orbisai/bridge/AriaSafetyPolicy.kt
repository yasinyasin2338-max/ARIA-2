package com.orbisai.bridge

import android.app.KeyguardManager
import android.content.Context

/**
 * Hard safety boundary for remote ARIA control.
 * The bridge never inspects or interacts with lock screens, password/authenticator
 * apps, Secure Folder/Knox containers, or banking/payment apps.
 */
object AriaSafetyPolicy {
    private val exactProtectedPackages = setOf(
        "com.samsung.knox.securefolder",
        "com.samsung.android.knox.containercore",
        "com.samsung.android.spay",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
        "com.revolut.revolut",
        "de.number26.android",
        "com.bunq.android",
        "com.abnamro.nl.mobile.payments",
        "nl.rabomobiel"
    )

    private val packageMarkers = listOf(
        "securefolder", ".knox", "banking", "mobilebank", ".bank.", ".banking.",
        "wallet", "authenticator", "passwordmanager", "password.manager"
    )

    private val labelMarkers = listOf(
        "bank", "banking", "بانک", "همراه بانک", "secure folder", "knox",
        "wallet", "کیف پول", "authenticator", "password", "رمز"
    )

    fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    fun isProtectedPackage(context: Context, packageName: String?): Boolean {
        val pkg = packageName.orEmpty().trim().lowercase()
        if (pkg.isBlank()) return false
        if (pkg in exactProtectedPackages) return true
        if (packageMarkers.any { pkg.contains(it) }) return true

        val label = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString().lowercase()
        }.getOrDefault("")
        return labelMarkers.any { label.contains(it) }
    }

    fun state(context: Context, packageName: String?): String? {
        if (isLocked(context)) return "PROTECTED_LOCK_SCREEN"
        if (isProtectedPackage(context, packageName)) return "PROTECTED_APP"
        return null
    }

    /** Redacts standalone 4-8 digit values that commonly contain OTP/PIN codes. */
    fun sanitizeNotificationText(value: CharSequence?): String = value?.toString().orEmpty()
        .replace(Regex("(?<!\\d)\\d{4,8}(?!\\d)"), "[عدد حساس]")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)
}
