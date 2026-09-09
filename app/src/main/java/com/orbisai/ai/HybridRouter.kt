package com.orbisai.ai

/** Chooses an execution mode without exposing provider credentials to the UI. */
class HybridRouter(
    private val onlineAvailable: () -> Boolean = { false },
) {
    fun route(requested: Mode, preferOffline: Boolean = false): Mode = when (requested) {
        Mode.OFFLINE -> Mode.OFFLINE
        Mode.ONLINE -> if (onlineAvailable()) Mode.ONLINE else Mode.OFFLINE
        Mode.AUTO -> if (preferOffline || !onlineAvailable()) Mode.OFFLINE else Mode.ONLINE
    }
}
