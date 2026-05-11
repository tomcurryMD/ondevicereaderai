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

    init {
        refreshVoices()
    }

    private fun refreshVoices() {
        _voices.value = ttsEngine.getDownloadedVoices()
    }

    fun downloadVoice(voice: VoiceModel) {
        viewModelScope.launch {
            _downloadingVoiceId.value = voice.id
            val success = ttsEngine.downloadVoice(voice)
            _downloadingVoiceId.value = null
            if (success) {
                refreshVoices()
                // Auto-select if first voice
                val downloaded = ttsEngine.getDownloadedVoices().filter { it.isDownloaded }
                if (downloaded.size == 1) {
                    selectVoice(voice.id)
                }
            }
        }
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
