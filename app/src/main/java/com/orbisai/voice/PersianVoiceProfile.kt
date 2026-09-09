package com.orbisai.voice

/** First-class Persian voice configuration for every agent. */
data object PersianVoiceProfile {
    const val localeTag = "fa-IR"
    const val sampleRateHz = 16_000
    const val languageName = "فارسی"

    // Persian text normalization used before STT/LLM/TTS hand-off.
    fun normalize(text: String): String = text
        .replace('\u064A', '\u06CC') // Arabic ي -> Persian ی
        .replace('\u0643', '\u06A9') // Arabic ك -> Persian ک
        .replace('\u0640'.toString(), "") // tatweel
        .replace(Regex("[\\u200C\\u200D]"), "\u200C")
        .trim()
}

/** Per-agent voice settings; the concrete TTS provider can be swapped later. */
data class PersianAgentVoice(
    val agentId: String,
    val localeTag: String = PersianVoiceProfile.localeTag,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
)

object PersianAgentVoices {
    val manager = PersianAgentVoice("manager", rate = 0.98f, pitch = 0.95f)
    val researcher = PersianAgentVoice("researcher", rate = 1.02f, pitch = 1.08f)
    val creative = PersianAgentVoice("creative", rate = 1.04f, pitch = 1.12f)
}
