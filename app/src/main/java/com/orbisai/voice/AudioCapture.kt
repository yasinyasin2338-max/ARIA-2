package com.orbisai.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Local 16 kHz mono PCM capture foundation with lightweight energy VAD. */
class AudioCapture(private val sampleRate: Int = 16_000) {
    suspend fun capture(onFrame: suspend (ShortArray, Float) -> Unit) = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "AudioRecord buffer unavailable" }
        val buffer = ShortArray(maxOf(min / 2, 320))
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, buffer.size * 2))
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) { val v = buffer[i].toDouble() / Short.MAX_VALUE; sum += v * v }
                    onFrame(buffer.copyOf(read), sqrt(sum / read).toFloat())
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
