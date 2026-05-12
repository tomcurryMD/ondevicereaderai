package com.readertomeai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readertomeai.ReaderToMeApp
import com.readertomeai.data.model.AvailableVoices
import com.readertomeai.data.model.ReaderMode
import com.readertomeai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val settings = ReaderToMeApp.instance.settingsRepository
    private val ttsEngine = ReaderToMeApp.instance.ttsEngine

    val ttsSpeed = settings.ttsSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_TTS_SPEED)

    val readingTheme = settings.readingTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_READING_THEME)

    val autoScrollDuringTts = settings.autoScrollDuringTts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val highlightDuringTts = settings.highlightDuringTts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val selectedVoiceName: StateFlow<String> = settings.selectedVoiceId
        .map { voiceId ->
            AvailableVoices.voices.find { it.id == voiceId }?.name ?: "Not set"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Not set")

    val selectedHumanVoiceName: StateFlow<String> = settings.selectedHumanVoiceId
        .map { voiceId ->
            AvailableVoices.voices.find { it.id == voiceId }?.name ?: "Human Reader"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Human Reader")

    val readerMode = settings.readerMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderMode.INSTANT)

    fun setTtsSpeed(speed: Float) {
        viewModelScope.launch {
            settings.setTtsSpeed(speed)
            ttsEngine.speed = speed
        }
    }

    fun setAutoScrollDuringTts(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoScrollDuringTts(enabled) }
    }

    fun setHighlightDuringTts(enabled: Boolean) {
        viewModelScope.launch { settings.setHighlightDuringTts(enabled) }
    }

    fun setReadingTheme(theme: String) {
        viewModelScope.launch { settings.setReadingTheme(theme) }
    }

    fun setReaderMode(mode: ReaderMode) {
        viewModelScope.launch { settings.setReaderMode(mode) }
    }
}
