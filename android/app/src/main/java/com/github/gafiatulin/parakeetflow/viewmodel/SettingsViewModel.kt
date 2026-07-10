package com.github.gafiatulin.parakeetflow.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gafiatulin.parakeetflow.core.model.MdContentMode
import com.github.gafiatulin.parakeetflow.core.model.UserSettings
import com.github.gafiatulin.parakeetflow.core.preferences.PreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    val settings: StateFlow<UserSettings> = preferencesDataStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    fun setLlmEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setLlmEnabled(enabled) }
    }

    fun setFillerFilterEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setFillerFilterEnabled(enabled) }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setHapticFeedback(enabled) }
    }

    fun setAudioFeedback(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setAudioFeedback(enabled) }
    }

    fun setLlmGpu(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setLlmGpu(enabled) }
    }

    fun setLingeringBubble(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setLingeringBubble(enabled) }
    }

    fun setHfToken(token: String) {
        viewModelScope.launch { preferencesDataStore.setHfToken(token) }
    }

    fun setMdExportEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setMdExportEnabled(enabled) }
    }

    fun setExportFolderUri(uri: String) {
        viewModelScope.launch { preferencesDataStore.setExportFolderUri(uri) }
    }

    fun setMdContentMode(mode: MdContentMode) {
        viewModelScope.launch { preferencesDataStore.setMdContentMode(mode) }
    }

    fun setMdFrontmatter(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setMdFrontmatter(enabled) }
    }

    fun setAudioExportEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setAudioExportEnabled(enabled) }
    }

    fun setAudioFolderUri(uri: String) {
        viewModelScope.launch { preferencesDataStore.setAudioFolderUri(uri) }
    }

    fun setBubbleSizeDp(size: Int) {
        viewModelScope.launch { preferencesDataStore.setBubbleSizeDp(size) }
    }

    fun setBubbleColor(color: Int) {
        viewModelScope.launch { preferencesDataStore.setBubbleColor(color) }
    }

    fun setBubbleOpacity(opacity: Float) {
        viewModelScope.launch { preferencesDataStore.setBubbleOpacity(opacity) }
    }
}
