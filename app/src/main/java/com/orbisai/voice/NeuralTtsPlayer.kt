package com.orbisai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Plays high-quality Persian speech returned by the ARIA backend. */
class NeuralTtsPlayer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    @Volatile private var closed = false
    @Volatile private var requestSerial = 0L

    fun speak(
        text: String,
        onStarted: () -> Unit = {},
        onDone: () -> Unit,
        onError: () -> Unit
    ) {
        val clean = text.trim().take(1800)
        if (clean.isBlank() || closed) return onError()
        val serial = System.nanoTime().also { requestSerial = it }
        stopPlayerOnly()

        thread(name = "aria-neural-tts", isDaemon = true) {
            val payload = fetchAudio(clean)
            if (closed || serial != requestSerial || payload == null || payload.bytes.size < 256) {
                main.post { if (!closed && serial == requestSerial) onError() }
                return@thread
            }

            val file = runCatching {
                File.createTempFile("aria-neural-", payload.extension, appContext.cacheDir).apply {
                    writeBytes(payload.bytes)
                }
            }.getOrNull()
            if (file == null) {
                main.post { if (!closed && serial == requestSerial) onError() }
                return@thread
            }

            main.post {
                if (closed || serial != requestSerial) {
                    file.delete(); return@post
                }
                cleanupPlayer()
                audioFile = file
                val mp = MediaPlayer()
                player = mp
                runCatching {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener {
                        if (!closed && serial == requestSerial) {
                            onStarted()
                            it.start()
                        }
                    }
                    mp.setOnCompletionListener {
                        cleanupPlayer()
                        if (!closed && serial == requestSerial) onDone()
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        cleanupPlayer()
                        if (!closed && serial == requestSerial) onError()
                        true
                    }
                    mp.prepareAsync()
                }.onFailure {
                    cleanupPlayer()
                    if (!closed && serial == requestSerial) onError()
                }
            }
        }
    }

    private fun fetchAudio(text: String): AudioPayload? = runCatching {
        val conn = (URL(TTS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 95_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/*")
            setRequestProperty("User-Agent", "ARIA-Android-Neural-TTS/0.9.1")
        }
        val body = JSONObject()
            .put("text", text)
            .put("voice", "male")
            .toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val contentType = conn.contentType.orEmpty().lowercase()
        val data = if (code in 200..299 && contentType.startsWith("audio/")) {
            conn.inputStream.use { it.readBytes() }
        } else null
        conn.disconnect()
        if (data == null) null
        else AudioPayload(data, if (contentType.contains("wav")) ".wav" else ".mp3")
    }.getOrNull()

    fun stop() {
        requestSerial = System.nanoTime()
        main.post { cleanupPlayer() }
    }

    private fun stopPlayerOnly() {
        main.post { cleanupPlayer() }
    }

    private fun cleanupPlayer() {
        val p = player
        player = null
        runCatching { p?.stop() }
        runCatching { p?.reset() }
        runCatching { p?.release() }
        audioFile?.delete()
        audioFile = null
    }

    override fun close() {
        closed = true
        requestSerial = System.nanoTime()
        main.post { cleanupPlayer() }
    }

    private data class AudioPayload(val bytes: ByteArray, val extension: String)

    companion object {
        private const val TTS_URL = "https://aria-server-new-production.up.railway.app/api/tts"
    }
}
