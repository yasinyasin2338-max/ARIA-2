package com.orbisai.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import com.orbisai.voice.PersianVoiceProfile

/** Forces correct RTL layout when the assistant is operating in Persian. */
@Composable
fun PersianLayout(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        content()
    }
}

object OrbisLocale {
    const val defaultLanguage = PersianVoiceProfile.localeTag
}
