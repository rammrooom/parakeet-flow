package com.github.gafiatulin.parakeetflow.core.model

data class UserSettings(
    val llmEnabled: Boolean = true,
    val fillerFilterEnabled: Boolean = true,
    val hapticFeedback: Boolean = true,
    val audioFeedback: Boolean = true,
    val bubblePosition: BubblePosition = BubblePosition(x = -1, y = -1),
    val autoCapitalize: Boolean = true,
    val autoPunctuation: Boolean = true,
    val llmGpu: Boolean = true,
    val lingeringBubble: Boolean = false,
    val hfToken: String = "",
    // Markdown transcript export
    val mdExportEnabled: Boolean = false,
    val exportFolderUri: String = "",
    val mdContentMode: MdContentMode = MdContentMode.FINAL,
    val mdFrontmatter: Boolean = true,
    // Audio (WAV) export
    val audioExportEnabled: Boolean = false,
    val audioFolderUri: String = "",
    // Bubble appearance
    val bubbleSizeDp: Int = 64,
    val bubbleColor: Int = DEFAULT_BUBBLE_COLOR,
    val bubbleOpacity: Float = 1f,
    // When recording, show the pause/cancel controls beside the bubble (on the
    // side toward screen center) instead of below it.
    val bubbleControlsBeside: Boolean = true
)

/** Default idle bubble color (ParakeetGreen, 0xFF4CAF50) as a packed ARGB int. */
const val DEFAULT_BUBBLE_COLOR: Int = 0xFF4CAF50.toInt()

data class BubblePosition(val x: Int, val y: Int)

/** Which text a saved Markdown transcript should contain. */
enum class MdContentMode {
    /** Only the final inserted text (after filler filter / LLM cleanup). */
    FINAL,

    /** Only the raw ASR output, before any filtering. */
    RAW,

    /** All pipeline stages: raw, filtered and final. */
    FULL
}
