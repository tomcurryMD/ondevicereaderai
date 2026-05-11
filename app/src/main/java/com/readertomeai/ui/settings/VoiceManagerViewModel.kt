package com.readertomeai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readertomeai.ReaderToMeApp
import com.readertomeai.data.model.AvailableVoices
import com.readertomeai.data.model.VoiceModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VoiceManagerViewModel : ViewModel() {

    private val ttsEngine = ReaderToMeApp.instance.ttsEngine
    private val settings = ReaderToMeApp.instance.settingsRepository

    private val _voices = MutableStateFlow<List<VoiceModel>>(emptyList())
    val voices: StateFlow<List<VoiceModel>> = _voices

    val selectedVoiceId = settings.selectedVoiceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AvailableVoices.DEFAULT_VOICE_ID)

    private val _downloadingVoiceId = MutableStateFlow<String?>(null)
    val downloadingVoiceId: StateFlow<String?> = _downloadingVoiceId

    val downloadProgress = ttsEngine.downloadProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    init {
        refreshVoices()
    }

    private fun refreshVoices() {
        _voices.value = ttsEngine.getDownloadedVoices()
    }

    fun downloadVoice(voice: VoiceModel) {
        viewModelScope.launch {
            _downloadingVoiceId.value = voice.id
            _downloadError.value = null
            try {
                val success = ttsEngine.downloadVoice(voice)
                _downloadingVoiceId.value = null
                if (success) {
                    refreshVoices()
                    val downloaded = ttsEngine.getDownloadedVoices().filter { it.isDownloaded }
                    if (downloaded.size == 1) {
                        selectVoice(voice.id)
                    }
                } else {
                    _downloadError.value = "Download failed for ${voice.name}. Check your internet connection and try again."
                }
            } catch (e: Exception) {
                _downloadingVoiceId.value = null
                _downloadError.value = "Download failed: ${e.message ?: "Unknown error"}. Tap to retry."
            }
        }
    }

    fun clearError() {
        _downloadError.value = null
    }

    fun selectVoice(voiceId: String) {
        viewModelScope.launch {
            settings.setSelectedVoice(voiceId)
            ttsEngine.initializeVoice(voiceId)
        }
    }

    fun deleteVoice(voice: VoiceModel) {
        ttsEngine.deleteVoice(voice)
        refreshVoices()
    }
}
