package com.orbisai.ai

/** Keeps model binaries outside the APK and exposes readiness to the router/UI. */
data class LocalModel(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

interface ModelManager {
    fun installedModels(): List<LocalModel>
    fun isReady(modelId: String): Boolean
}

class EmptyModelManager : ModelManager {
    override fun installedModels(): List<LocalModel> = emptyList()
    override fun isReady(modelId: String): Boolean = false
}
