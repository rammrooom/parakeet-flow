package com.github.gafiatulin.parakeetflow.orchestrator

import android.util.Log
import com.github.gafiatulin.parakeetflow.asr.TranscriptionEngine
import com.github.gafiatulin.parakeetflow.audio.AudioCaptureManager
import com.github.gafiatulin.parakeetflow.context.ContextReader
import com.github.gafiatulin.parakeetflow.core.model.AppPhase
import com.github.gafiatulin.parakeetflow.core.model.TranscriptionRecord
import com.github.gafiatulin.parakeetflow.core.util.FillerWordFilter
import com.github.gafiatulin.parakeetflow.export.TranscriptExporter
import com.github.gafiatulin.parakeetflow.feedback.FeedbackManager
import com.github.gafiatulin.parakeetflow.history.HistoryRepository
import com.github.gafiatulin.parakeetflow.insertion.TextInserter
import com.github.gafiatulin.parakeetflow.llm.PostProcessor
import com.github.gafiatulin.parakeetflow.model.ModelManager
import com.github.gafiatulin.parakeetflow.service.ServiceBridge
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main pipeline orchestrator for the dictation flow.
 *
 * Coordinates the complete pipeline:
 * 1. Audio capture via [AudioCaptureManager]
 * 2. Speech-to-text via [TranscriptionEngine]
 * 3. Filler word filtering via [FillerWordFilter]
 * 4. Context reading via [ContextReader]
 * 5. LLM post-processing via [PostProcessor]
 * 6. Text insertion via [TextInserter]
 *
 * Phase transitions are published through [ServiceBridge] for UI observation.
 */
@Singleton
class DictationOrchestrator @Inject constructor(
    private val audioCaptureManager: AudioCaptureManager,
    private val transcriptionEngine: TranscriptionEngine,
    private val postProcessor: PostProcessor,
    private val contextReader: ContextReader,
    private val textInserter: TextInserter,
    private val serviceBridge: ServiceBridge,
    private val modelManager: ModelManager,
    private val historyRepository: HistoryRepository,
    private val preferencesDataStore: com.github.gafiatulin.parakeetflow.core.preferences.PreferencesDataStore,
    private val feedbackManager: FeedbackManager,
    private val transcriptExporter: TranscriptExporter
) {
    companion object {
        private const val TAG = "DictationOrchestrator"
        private const val ERROR_DISPLAY_DURATION_MS = 2000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var isRecording = false

    /**
     * Initializes the ASR engine with models from ModelManager.
     * Must be called before the first transcription attempt.
     */
    suspend fun initializeEngine(force: Boolean = false): Boolean {
        if (!force && transcriptionEngine.isReady) return true

        if (force) {
            Log.i(TAG, "Force reinitializing engines")
            transcriptionEngine.release()
            postProcessor.release()
        }

        val modelDir = modelManager.getAsrModelDir()
        if (!modelDir.exists()) {
            Log.e(TAG, "ASR model directory does not exist: ${modelDir.absolutePath}")
            return false
        }

        Log.i(TAG, "Initializing ASR engine from ${modelDir.absolutePath}")
        val success = transcriptionEngine.initialize(modelDir.absolutePath)
        if (success) {
            Log.i(TAG, "ASR engine initialized successfully")
        } else {
            Log.e(TAG, "ASR engine initialization failed")
        }

        // Initialize LLM in background only if enabled
        scope.launch {
            val settings = preferencesDataStore.settings.first()
            if (settings.llmEnabled) {
                initializeLlm()
            } else {
                Log.i(TAG, "LLM disabled, skipping initialization")
            }
        }

        return success
    }

    private suspend fun initializeLlm() {
        if (postProcessor.isReady) return
        val llmPath = modelManager.getLlmModelPath()
        if (java.io.File(llmPath).exists()) {
            val useGpu = preferencesDataStore.settings.first().llmGpu
            Log.i(TAG, "Initializing LLM from $llmPath (gpu=$useGpu)")
            postProcessor.initialize(llmPath, useGpu)
        }
    }

    /**
     * Toggles between recording and processing states.
     * If currently recording, stops and processes. Otherwise, starts recording.
     */
    fun toggleRecording() {
        Log.d(TAG, "toggleRecording() called, isRecording=$isRecording")
        if (isRecording) {
            stopRecordingAndProcess()
        } else {
            startRecording()
        }
    }

    /**
     * Starts audio capture. No-op if already recording.
     */
    fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring startRecording()")
            return
        }

        try {
            isRecording = true
            serviceBridge.updatePhase(AppPhase.RECORDING)
            audioCaptureManager.startCapture()
            scope.launch { feedbackManager.onRecordingStart() }
            Log.i(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording = false
            handleError()
        }
    }

    /**
     * Stops recording and runs the full processing pipeline:
     * transcribe -> filter -> read context -> LLM cleanup -> insert.
     */
    fun stopRecordingAndProcess() {
        if (!isRecording) {
            Log.w(TAG, "Not recording, ignoring stopRecordingAndProcess()")
            return
        }
        isRecording = false

        scope.launch {
            try {
                serviceBridge.updatePhase(AppPhase.PROCESSING)
                feedbackManager.onRecordingStop()

                // 1. Stop recording, get all accumulated audio samples
                val samples = audioCaptureManager.stopCapture()
                if (samples.isEmpty()) {
                    Log.d(TAG, "No audio samples captured")
                    serviceBridge.updatePhase(AppPhase.IDLE)
                    return@launch
                }
                Log.d(TAG, "Captured ${samples.size} samples (${samples.size / AudioCaptureManager.SAMPLE_RATE}s)")

                // 2. Transcribe audio to text
                if (!transcriptionEngine.isReady) {
                    Log.i(TAG, "Engine not ready, initializing...")
                    if (!initializeEngine()) {
                        Log.e(TAG, "Transcription engine could not be initialized")
                        handleError()
                        return@launch
                    }
                }
                val rawText = transcriptionEngine.transcribe(samples)
                if (rawText.isBlank()) {
                    Log.d(TAG, "Transcription produced empty text")
                    serviceBridge.updatePhase(AppPhase.IDLE)
                    return@launch
                }
                Log.d(TAG, "Raw transcription: $rawText")

                val settings = preferencesDataStore.settings.first()

                // 3. Filter filler words
                val filteredText = if (settings.fillerFilterEnabled) {
                    FillerWordFilter.filter(rawText)
                } else {
                    rawText
                }
                Log.d(TAG, "Filtered text: $filteredText")

                // 4. Read context from the currently focused app
                val appContext = contextReader.readCurrentContext()
                Log.d(TAG, "App context: ${appContext.appLabel} / ${appContext.activityTitle}")

                // 5. LLM post-processing (if enabled and available)
                val cleanedText = if (settings.llmEnabled && postProcessor.isReady) {
                    postProcessor.cleanup(filteredText, appContext)
                } else {
                    filteredText
                }
                Log.d(TAG, "Cleaned text: $cleanedText")

                // 6. Insert text or copy to clipboard
                serviceBridge.updatePhase(AppPhase.INSERTING)
                val inserted = textInserter.insert(cleanedText)
                if (!inserted) {
                    Log.i(TAG, "No text field or insertion failed — text copied to clipboard")
                }

                // 7. Save to history
                val record = TranscriptionRecord(
                    id = UUID.randomUUID().toString(),
                    rawText = rawText,
                    filteredText = filteredText,
                    cleanedText = cleanedText,
                    appContext = appContext.appLabel,
                    timestampMillis = System.currentTimeMillis(),
                    durationMillis = (samples.size * 1000L) / AudioCaptureManager.SAMPLE_RATE
                )
                historyRepository.add(record)

                // 8. Optional export to a user-selected folder (Markdown + audio)
                transcriptExporter.export(record, samples, settings)

                serviceBridge.updatePhase(AppPhase.IDLE)
                Log.i(TAG, "Dictation pipeline complete")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                handleError()
            }
        }
    }

    /**
     * Pauses the current recording. Audio keeps flowing from the mic but is
     * dropped until [resumeRecording]. No-op if not recording or already paused.
     */
    fun pauseRecording() {
        if (!isRecording) return
        audioCaptureManager.pause()
        serviceBridge.updatePhase(AppPhase.PAUSED)
        Log.i(TAG, "Recording paused")
    }

    /**
     * Resumes a paused recording. No-op if not recording.
     */
    fun resumeRecording() {
        if (!isRecording) return
        audioCaptureManager.resume()
        serviceBridge.updatePhase(AppPhase.RECORDING)
        Log.i(TAG, "Recording resumed")
    }

    /**
     * Cancels the current recording without processing.
     */
    fun cancelRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            audioCaptureManager.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture during cancel", e)
        }

        serviceBridge.updatePhase(AppPhase.IDLE)
        Log.i(TAG, "Recording cancelled")
    }

    /**
     * Releases all resources. Call when the service is being destroyed.
     */
    fun release() {
        Log.i(TAG, "Releasing orchestrator resources")
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            transcriptionEngine.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing transcription engine", e)
        }
        try {
            postProcessor.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing post-processor", e)
        }
    }

    private fun handleError() {
        scope.launch {
            serviceBridge.updatePhase(AppPhase.ERROR)
            feedbackManager.onError()
            delay(ERROR_DISPLAY_DURATION_MS)
            serviceBridge.updatePhase(AppPhase.IDLE)
        }
    }
}
