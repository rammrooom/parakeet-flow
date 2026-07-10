package com.github.gafiatulin.parakeetflow.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioCaptureManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val CHUNK_SIZE_SAMPLES = 1600 // 100ms at 16kHz
    }

    private val _audioChunks = MutableSharedFlow<FloatArray>(extraBufferCapacity = 64)
    val audioChunks: SharedFlow<FloatArray> = _audioChunks

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val accumulatedSamples = mutableListOf<FloatArray>()
    private val accumulationLock = Any()

    @Volatile
    private var paused = false

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun startCapture() {
        if (!hasPermission) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        paused = false
        synchronized(accumulationLock) {
            accumulatedSamples.clear()
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE_SAMPLES * Float.SIZE_BYTES * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord = record
        record.startRecording()

        recordingJob = recordingScope.launch {
            val buffer = FloatArray(CHUNK_SIZE_SAMPLES)
            while (isActive) {
                val readResult = record.read(
                    buffer, 0, CHUNK_SIZE_SAMPLES, AudioRecord.READ_BLOCKING
                )
                if (readResult > 0) {
                    // While paused, keep draining the mic buffer but drop the
                    // samples so the paused span is skipped in the transcript.
                    if (!paused) {
                        val chunk = buffer.copyOf(readResult)

                        synchronized(accumulationLock) {
                            accumulatedSamples.add(chunk)
                        }

                        _audioChunks.tryEmit(chunk)
                    }
                } else if (readResult < 0) {
                    break
                }
            }
        }
    }

    /** Pauses accumulation; the microphone keeps running but samples are dropped. */
    fun pause() {
        paused = true
    }

    /** Resumes accumulation after [pause]. */
    fun resume() {
        paused = false
    }

    fun stopCapture(): FloatArray {
        paused = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let { record ->
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record.release()
        }
        audioRecord = null

        val allSamples: FloatArray
        synchronized(accumulationLock) {
            val totalSize = accumulatedSamples.sumOf { it.size }
            allSamples = FloatArray(totalSize)
            var offset = 0
            for (chunk in accumulatedSamples) {
                chunk.copyInto(allSamples, offset)
                offset += chunk.size
            }
            accumulatedSamples.clear()
        }

        return allSamples
    }
}
