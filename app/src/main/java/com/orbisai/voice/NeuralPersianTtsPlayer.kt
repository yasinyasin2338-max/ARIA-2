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

class NeuralPersianTtsPlayer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var audioFile: File? = null

    fun speak(
        text: String,
        onStarted: () -> Unit,
        onDone: () -> Unit,
        onFallback: () -> Unit
    ) {
        stop()
        thread(name = "aris-neural-tts", isDaemon = true) {
            val file = runCatching { requestAudio(text) }.getOrNull()
            if (file == null) {
                main.post(onFallback)
                return@thread
            }
            main.post {
                runCatching {
                    audioFile = file
                    player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(file.absolutePath)
                        setOnPreparedListener {
                            onStarted()
                            it.start()
                        }
                        setOnCompletionListener {
                            cleanupPlayer()
                            onDone()
                        }
                        setOnErrorListener { _, _, _ ->
                            cleanupPlayer()
                            onFallback()
                            true
                        }
                        prepareAsync()
                    }
                }.onFailure {
                    cleanupPlayer()
                    onFallback()
                }
            }
        }
    }

    private fun requestAudio(text: String): File {
        val conn = (URL(TTS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/mpeg")
            setRequestProperty("User-Agent", "ARIA-Android-Neural-TTS")
        }
        val payload = JSONObject()
            .put("text", text.take(2200))
            .put("voice", "male")
            .put("rate", "-6%")
            .put("pitch", "-2Hz")
            .toString()
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.errorStream?.close()
            conn.disconnect()
            error("TTS HTTP $code")
        }
        val file = File.createTempFile("aris-neural-", ".mp3", appContext.cacheDir)
        conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        conn.disconnect()
        if (file.length() < 512L) {
            file.delete()
            error("TTS audio was empty")
        }
        return file
    }

    fun stop() {
        main.post { cleanupPlayer() }
    }

    private fun cleanupPlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        audioFile?.delete()
        audioFile = null
    }

    override fun close() {
        cleanupPlayer()
    }

    companion object {
        private const val TTS_URL = "https://aria-server-new-production.up.railway.app/api/tts"
    }
}
