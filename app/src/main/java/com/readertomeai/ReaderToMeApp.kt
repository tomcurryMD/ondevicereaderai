package com.readertomeai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.readertomeai.data.database.AppDatabase
import com.readertomeai.data.repository.BookRepository
import com.readertomeai.data.repository.SettingsRepository
import com.readertomeai.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReaderToMeApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var bookRepository: BookRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var ttsEngine: TtsEngine
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        bookRepository = BookRepository(database.bookDao(), this)
        settingsRepository = SettingsRepository(this)
        ttsEngine = TtsEngine(this)
        appScope.launch {
            settingsRepository.migrateTtsDefaultsIfNeeded()
            ttsEngine.ensureBundledDefaultVoiceInstalled()
            ttsEngine.initializeVoice(settingsRepository.selectedVoiceId.first())
        }

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "tts_playback"
        const val NOTIFICATION_ID = 1001

        lateinit var instance: ReaderToMeApp
            private set
    }
}
