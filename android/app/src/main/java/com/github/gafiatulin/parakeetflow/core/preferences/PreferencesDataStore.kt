package com.github.gafiatulin.parakeetflow.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.gafiatulin.parakeetflow.core.model.BubblePosition
import com.github.gafiatulin.parakeetflow.core.model.DEFAULT_BUBBLE_COLOR
import com.github.gafiatulin.parakeetflow.core.model.MdContentMode
import com.github.gafiatulin.parakeetflow.core.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private object Keys {
        val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        val FILLER_FILTER_ENABLED = booleanPreferencesKey("filler_filter_enabled")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val AUDIO_FEEDBACK = booleanPreferencesKey("audio_feedback")
        val BUBBLE_POSITION_X = intPreferencesKey("bubble_position_x")
        val BUBBLE_POSITION_Y = intPreferencesKey("bubble_position_y")
        val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        val AUTO_PUNCTUATION = booleanPreferencesKey("auto_punctuation")
        val LLM_GPU = booleanPreferencesKey("llm_gpu")
        val LINGERING_BUBBLE = booleanPreferencesKey("lingering_bubble")
        val HF_TOKEN = stringPreferencesKey("hf_token")
        val MD_EXPORT_ENABLED = booleanPreferencesKey("md_export_enabled")
        val EXPORT_FOLDER_URI = stringPreferencesKey("export_folder_uri")
        val MD_CONTENT_MODE = stringPreferencesKey("md_content_mode")
        val MD_FRONTMATTER = booleanPreferencesKey("md_frontmatter")
        val AUDIO_EXPORT_ENABLED = booleanPreferencesKey("audio_export_enabled")
        val AUDIO_FOLDER_URI = stringPreferencesKey("audio_folder_uri")
        val BUBBLE_SIZE_DP = intPreferencesKey("bubble_size_dp")
        val BUBBLE_COLOR = intPreferencesKey("bubble_color")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble_opacity")
        val BUBBLE_CONTROLS_BESIDE = booleanPreferencesKey("bubble_controls_beside")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            llmEnabled = prefs[Keys.LLM_ENABLED] ?: true,
            fillerFilterEnabled = prefs[Keys.FILLER_FILTER_ENABLED] ?: true,
            hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
            audioFeedback = prefs[Keys.AUDIO_FEEDBACK] ?: true,
            bubblePosition = BubblePosition(
                x = prefs[Keys.BUBBLE_POSITION_X] ?: -1,
                y = prefs[Keys.BUBBLE_POSITION_Y] ?: -1
            ),
            autoCapitalize = prefs[Keys.AUTO_CAPITALIZE] ?: true,
            autoPunctuation = prefs[Keys.AUTO_PUNCTUATION] ?: true,
            llmGpu = prefs[Keys.LLM_GPU] ?: true,
            lingeringBubble = prefs[Keys.LINGERING_BUBBLE] ?: false,
            hfToken = prefs[Keys.HF_TOKEN] ?: "",
            mdExportEnabled = prefs[Keys.MD_EXPORT_ENABLED] ?: false,
            exportFolderUri = prefs[Keys.EXPORT_FOLDER_URI] ?: "",
            mdContentMode = prefs[Keys.MD_CONTENT_MODE]
                ?.let { runCatching { MdContentMode.valueOf(it) }.getOrNull() }
                ?: MdContentMode.FINAL,
            mdFrontmatter = prefs[Keys.MD_FRONTMATTER] ?: true,
            audioExportEnabled = prefs[Keys.AUDIO_EXPORT_ENABLED] ?: false,
            audioFolderUri = prefs[Keys.AUDIO_FOLDER_URI] ?: "",
            bubbleSizeDp = prefs[Keys.BUBBLE_SIZE_DP] ?: 64,
            bubbleColor = prefs[Keys.BUBBLE_COLOR] ?: DEFAULT_BUBBLE_COLOR,
            bubbleOpacity = prefs[Keys.BUBBLE_OPACITY] ?: 1f,
            bubbleControlsBeside = prefs[Keys.BUBBLE_CONTROLS_BESIDE] ?: true
        )
    }

    suspend fun setLlmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LLM_ENABLED] = enabled }
    }

    suspend fun setFillerFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FILLER_FILTER_ENABLED] = enabled }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setAudioFeedback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_FEEDBACK] = enabled }
    }

    suspend fun setBubblePosition(position: BubblePosition) {
        context.dataStore.edit {
            it[Keys.BUBBLE_POSITION_X] = position.x
            it[Keys.BUBBLE_POSITION_Y] = position.y
        }
    }

    suspend fun setAutoCapitalize(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CAPITALIZE] = enabled }
    }

    suspend fun setAutoPunctuation(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PUNCTUATION] = enabled }
    }

    suspend fun setLlmGpu(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LLM_GPU] = enabled }
    }

    suspend fun setLingeringBubble(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LINGERING_BUBBLE] = enabled }
    }

    suspend fun setHfToken(token: String) {
        context.dataStore.edit { it[Keys.HF_TOKEN] = token }
    }

    suspend fun setMdExportEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MD_EXPORT_ENABLED] = enabled }
    }

    suspend fun setExportFolderUri(uri: String) {
        context.dataStore.edit { it[Keys.EXPORT_FOLDER_URI] = uri }
    }

    suspend fun setMdContentMode(mode: MdContentMode) {
        context.dataStore.edit { it[Keys.MD_CONTENT_MODE] = mode.name }
    }

    suspend fun setMdFrontmatter(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MD_FRONTMATTER] = enabled }
    }

    suspend fun setAudioExportEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_EXPORT_ENABLED] = enabled }
    }

    suspend fun setAudioFolderUri(uri: String) {
        context.dataStore.edit { it[Keys.AUDIO_FOLDER_URI] = uri }
    }

    suspend fun setBubbleSizeDp(size: Int) {
        context.dataStore.edit { it[Keys.BUBBLE_SIZE_DP] = size }
    }

    suspend fun setBubbleColor(color: Int) {
        context.dataStore.edit { it[Keys.BUBBLE_COLOR] = color }
    }

    suspend fun setBubbleOpacity(opacity: Float) {
        context.dataStore.edit { it[Keys.BUBBLE_OPACITY] = opacity }
    }

    suspend fun setBubbleControlsBeside(beside: Boolean) {
        context.dataStore.edit { it[Keys.BUBBLE_CONTROLS_BESIDE] = beside }
    }
}
