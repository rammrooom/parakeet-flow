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
    val audioFolderUri: String = ""
)

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
