package com.readertomeai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.readertomeai.data.model.AvailableVoices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    // Reading settings
    val fontSize: Flow<Float> = context.dataStore.data.map { it[FONT_SIZE] ?: 18f }
    val lineSpacing: Flow<Float> = context.dataStore.data.map { it[LINE_SPACING] ?: 1.6f }
    val readingTheme: Flow<String> = context.dataStore.data.map { it[READING_THEME] ?: DEFAULT_READING_THEME }
    val fontFamily: Flow<String> = context.dataStore.data.map { it[FONT_FAMILY] ?: "default" }
    val margins: Flow<Int> = context.dataStore.data.map { it[MARGINS] ?: 16 }

    // TTS settings
    val selectedVoiceId: Flow<String> = context.dataStore.data.map {
        it[SELECTED_VOICE] ?: AvailableVoices.DEFAULT_VOICE_ID
    }
    val ttsSpeed: Flow<Float> = context.dataStore.data.map { it[TTS_SPEED] ?: DEFAULT_TTS_SPEED }
    val ttsPitch: Flow<Float> = context.dataStore.data.map { it[TTS_PITCH] ?: 1.0f }
    val autoScrollDuringTts: Flow<Boolean> = context.dataStore.data.map { it[AUTO_SCROLL_TTS] ?: true }
    val highlightDuringTts: Flow<Boolean> = context.dataStore.data.map { it[HIGHLIGHT_TTS] ?: true }

    // Library settings
    val sortOrder: Flow<String> = context.dataStore.data.map { it[SORT_ORDER] ?: "recent" }
    val gridView: Flow<Boolean> = context.dataStore.data.map { it[GRID_VIEW] ?: true }

    suspend fun setFontSize(size: Float) {
        context.dataStore.edit { it[FONT_SIZE] = size }
    }

    suspend fun setLineSpacing(spacing: Float) {
        context.dataStore.edit { it[LINE_SPACING] = spacing }
    }

    suspend fun setReadingTheme(theme: String) {
        context.dataStore.edit { it[READING_THEME] = theme }
    }

    suspend fun setFontFamily(family: String) {
        context.dataStore.edit { it[FONT_FAMILY] = family }
    }

    suspend fun setMargins(marginDp: Int) {
        context.dataStore.edit { it[MARGINS] = marginDp }
    }

    suspend fun setSelectedVoice(voiceId: String) {
        context.dataStore.edit { it[SELECTED_VOICE] = voiceId }
    }

    suspend fun setTtsSpeed(speed: Float) {
        context.dataStore.edit { it[TTS_SPEED] = speed }
    }

    suspend fun setTtsPitch(pitch: Float) {
        context.dataStore.edit { it[TTS_PITCH] = pitch }
    }

    suspend fun migrateTtsDefaultsIfNeeded() {
        context.dataStore.edit { prefs ->
            val version = prefs[TTS_DEFAULTS_VERSION] ?: 0
            if (version >= CURRENT_TTS_DEFAULTS_VERSION) return@edit

            val selectedVoice = prefs[SELECTED_VOICE]
            if (selectedVoice == null || selectedVoice == AvailableVoices.COMPACT_FALLBACK_VOICE_ID) {
                prefs[SELECTED_VOICE] = AvailableVoices.DEFAULT_VOICE_ID
            }

            val currentSpeed = prefs[TTS_SPEED]
            if (currentSpeed == null || currentSpeed == OLD_DEFAULT_TTS_SPEED) {
                prefs[TTS_SPEED] = DEFAULT_TTS_SPEED
            }

            prefs[TTS_DEFAULTS_VERSION] = CURRENT_TTS_DEFAULTS_VERSION
        }
    }

    suspend fun setAutoScrollDuringTts(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_SCROLL_TTS] = enabled }
    }

    suspend fun setHighlightDuringTts(enabled: Boolean) {
        context.dataStore.edit { it[HIGHLIGHT_TTS] = enabled }
    }

    suspend fun setSortOrder(order: String) {
        context.dataStore.edit { it[SORT_ORDER] = order }
    }

    suspend fun setGridView(grid: Boolean) {
        context.dataStore.edit { it[GRID_VIEW] = grid }
    }

    companion object {
        private val FONT_SIZE = floatPreferencesKey("font_size")
        private val LINE_SPACING = floatPreferencesKey("line_spacing")
        private val READING_THEME = stringPreferencesKey("reading_theme")
        private val FONT_FAMILY = stringPreferencesKey("font_family")
        private val MARGINS = intPreferencesKey("margins")
        private val SELECTED_VOICE = stringPreferencesKey("selected_voice")
        private val TTS_SPEED = floatPreferencesKey("tts_speed")
        private val TTS_DEFAULTS_VERSION = intPreferencesKey("tts_defaults_version")
        private val TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val AUTO_SCROLL_TTS = booleanPreferencesKey("auto_scroll_tts")
        private val HIGHLIGHT_TTS = booleanPreferencesKey("highlight_tts")
        private val SORT_ORDER = stringPreferencesKey("sort_order")
        private val GRID_VIEW = booleanPreferencesKey("grid_view")

        private const val OLD_DEFAULT_TTS_SPEED = 0.85f
        private const val CURRENT_TTS_DEFAULTS_VERSION = 2
        const val DEFAULT_READING_THEME = "light"
        const val DEFAULT_TTS_SPEED = 0.78f
    }
}
