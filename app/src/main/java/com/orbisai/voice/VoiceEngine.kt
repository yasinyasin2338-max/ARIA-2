package com.orbisai.voice

import kotlinx.coroutines.flow.Flow

sealed interface VoiceEvent {
    data object ListeningStarted : VoiceEvent
    data object ListeningStopped : VoiceEvent
    data class PartialTranscript(val text: String) : VoiceEvent
    data class FinalTranscript(val text: String) : VoiceEvent
    data object ThinkingStarted : VoiceEvent
    data class SpeechStarted(val text: String) : VoiceEvent
    data object SpeechFinished : VoiceEvent
    data class Error(val message: String) : VoiceEvent
}

interface VoiceEngine {
    val events: Flow<VoiceEvent>
    suspend fun startListening()
    suspend fun stopListening()
    suspend fun cancel()
    suspend fun speak(text: String)
}

class NoOpVoiceEngine : VoiceEngine {
    override val events: Flow<VoiceEvent> = kotlinx.coroutines.flow.emptyFlow()
    override suspend fun startListening() = Unit
    override suspend fun stopListening() = Unit
    override suspend fun cancel() = Unit
    override suspend fun speak(text: String) = Unit
}
