package com.github.gafiatulin.parakeetflow.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.github.gafiatulin.parakeetflow.audio.AudioCaptureManager
import com.github.gafiatulin.parakeetflow.core.model.MdContentMode
import com.github.gafiatulin.parakeetflow.core.model.TranscriptionRecord
import com.github.gafiatulin.parakeetflow.core.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes finished transcripts (and optionally the recorded audio) into a
 * user-selected folder via the Storage Access Framework.
 *
 * The folder is chosen in Settings with ACTION_OPEN_DOCUMENT_TREE; only the
 * persisted tree URI is used here, so the app never needs broad storage access.
 * Export is best-effort and must never break the dictation pipeline.
 */
@Singleton
class TranscriptExporter @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TranscriptExporter"
        private const val MIME_MARKDOWN = "text/markdown"
        private const val MIME_WAV = "audio/wav"
    }

    /**
     * Exports [record] as Markdown and, if enabled, [samples] as a WAV file.
     */
    suspend fun export(
        record: TranscriptionRecord,
        samples: FloatArray,
        settings: UserSettings
    ) = withContext(Dispatchers.IO) {
        val baseName = fileTimestamp(record.timestampMillis)

        if (settings.mdExportEnabled && settings.exportFolderUri.isNotBlank()) {
            val md = buildMarkdown(record, settings.mdContentMode, settings.mdFrontmatter)
            writeFile(
                settings.exportFolderUri,
                "$baseName.md",
                MIME_MARKDOWN,
                md.toByteArray(Charsets.UTF_8)
            )
        }

        if (settings.audioExportEnabled && samples.isNotEmpty()) {
            // Fall back to the Markdown folder when no dedicated audio folder is set.
            val audioFolder = settings.audioFolderUri.ifBlank { settings.exportFolderUri }
            if (audioFolder.isNotBlank()) {
                writeFile(audioFolder, "$baseName.wav", MIME_WAV, encodeWav(samples))
            }
        }
    }

    private fun writeFile(treeUriString: String, displayName: String, mime: String, bytes: ByteArray) {
        try {
            val treeUri = Uri.parse(treeUriString)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver, dirUri, mime, displayName
            )
            if (fileUri == null) {
                Log.e(TAG, "createDocument returned null for $displayName")
                return
            }
            context.contentResolver.openOutputStream(fileUri)?.use { it.write(bytes) }
            Log.i(TAG, "Exported $displayName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export $displayName", e)
        }
    }

    private fun buildMarkdown(
        record: TranscriptionRecord,
        mode: MdContentMode,
        frontmatter: Boolean
    ): String = buildString {
        if (frontmatter) {
            appendLine("---")
            appendLine("date: ${yaml(displayTimestamp(record.timestampMillis))}")
            appendLine("app: ${yaml(record.appContext)}")
            appendLine("duration: ${yaml(formatDuration(record.durationMillis))}")
            appendLine("---")
            appendLine()
        }

        appendLine("# Voice transcription")
        appendLine()

        when (mode) {
            MdContentMode.FINAL -> appendLine(record.cleanedText.trim())
            MdContentMode.RAW -> appendLine(record.rawText.trim())
            MdContentMode.FULL -> {
                appendLine("## Raw ASR")
                appendLine()
                appendLine(record.rawText.trim())
                appendLine()
                appendLine("## Filtered")
                appendLine()
                appendLine(record.filteredText.trim())
                appendLine()
                appendLine("## Final")
                appendLine()
                appendLine(record.cleanedText.trim())
            }
        }
    }

    /** Encodes 16 kHz mono float PCM samples as a 16-bit WAV byte array. */
    private fun encodeWav(samples: FloatArray): ByteArray {
        val sampleRate = AudioCaptureManager.SAMPLE_RATE
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = samples.size * 2

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            buffer.putShort((clamped * Short.MAX_VALUE).toInt().toShort())
        }
        return buffer.array()
    }

    /** Wraps a value as a double-quoted YAML scalar so colons/quotes are safe. */
    private fun yaml(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun fileTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(millis))

    private fun displayTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
