package com.nextgen.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

data class VoiceProfile(
    val meanPitch: Float = 0f,
    val stdPitch: Float = 0f,
    val isEnrolled: Boolean = false
)

object VoiceProfiler {
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val MIN_PITCH = 80f   // Hz
    private const val MAX_PITCH = 350f  // Hz

    /**
     * Records audio for 10 seconds via AudioRecord, extracting pitch from voiced frames.
     * Calls onProgress(progress 0f..1f, secondsLeft 10..0).
     */
    @SuppressLint("MissingPermission")
    suspend fun enrollUser(
        onProgress: (Float, Int) -> Unit
    ): Result<VoiceProfile> = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = minBuf.coerceAtLeast(4096)
        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(Exception("Microphone initialization failed"))
            }

            recorder.startRecording()
            val totalSeconds = 10
            val totalDurationMs = totalSeconds * 1000L
            val startTime = System.currentTimeMillis()
            val chunk = ShortArray(1024)
            val pitchList = mutableListOf<Float>()

            while (System.currentTimeMillis() - startTime < totalDurationMs) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    val p = detectPitch(chunk, read, SAMPLE_RATE)
                    if (p in MIN_PITCH..MAX_PITCH) {
                        pitchList.add(p)
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                val remainingSec = ((totalDurationMs - elapsed) / 1000L).toInt().coerceAtLeast(0)
                withContext(Dispatchers.Main) {
                    onProgress(progress, remainingSec)
                }
            }

            if (pitchList.size < 5) {
                return@withContext Result.failure(Exception("Not enough voice detected. Please speak clearly for 10 seconds."))
            }

            val mean = pitchList.average().toFloat()
            val variance = pitchList.map { (it - mean) * (it - mean) }.average()
            val std = sqrt(variance).toFloat()

            withContext(Dispatchers.Main) { onProgress(1f, 0) }
            Result.success(VoiceProfile(meanPitch = mean, stdPitch = std, isEnrolled = true))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                recorder?.stop()
                recorder?.release()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Pitch detection via Autocorrelation.
     */
    fun detectPitch(samples: ShortArray, length: Int, sampleRate: Int = SAMPLE_RATE): Float {
        val minLag = (sampleRate / MAX_PITCH).toInt()
        val maxLag = (sampleRate / MIN_PITCH).toInt()

        // Energy gate
        var energy = 0L
        for (i in 0 until length) {
            energy += samples[i].toLong() * samples[i].toLong()
        }
        val avgEnergy = energy / length
        if (avgEnergy < 100000) return 0f // Quiet frame

        var bestLag = 0
        var maxCorr = 0L

        for (lag in minLag until maxLag.coerceAtMost(length / 2)) {
            var corr = 0L
            val limit = length - lag
            for (i in 0 until limit) {
                corr += samples[i].toLong() * samples[i + lag].toLong()
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        return if (bestLag > 0) sampleRate.toFloat() / bestLag else 0f
    }

    /**
     * Extracts pitch from raw PCM byte buffer (16-bit little endian).
     */
    fun extractPitchFromBytes(bytes: ByteArray): Float {
        if (bytes.size < 512) return 0f
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        return detectPitch(shorts, shorts.size)
    }

    /**
     * Verifies if a detected pitch matches the enrolled user profile within tolerance.
     */
    fun matchesProfile(pitch: Float, profile: VoiceProfile): Boolean {
        if (!profile.isEnrolled || pitch <= 0f) return true
        val tolerance = (profile.stdPitch * 2.5f).coerceAtLeast(45f)
        return abs(pitch - profile.meanPitch) <= tolerance
    }
}
