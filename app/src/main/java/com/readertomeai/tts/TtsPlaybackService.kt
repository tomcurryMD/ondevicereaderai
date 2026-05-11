package com.readertomeai.tts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.readertomeai.MainActivity
import com.readertomeai.R
import com.readertomeai.ReaderToMeApp

class TtsPlaybackService : Service() {

    private var mediaSession: MediaSessionCompat? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "OnDeviceReaderAI").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPause() {
                    ReaderToMeApp.instance.ttsEngine.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                }
                override fun onPlay() {
                    ReaderToMeApp.instance.ttsEngine.resume()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }
                override fun onStop() {
                    handleStop()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: "Reading aloud"
                startForeground(ReaderToMeApp.NOTIFICATION_ID, createNotification(bookTitle))
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
            ACTION_PAUSE -> {
                ReaderToMeApp.instance.ttsEngine.pause()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
            ACTION_RESUME -> {
                ReaderToMeApp.instance.ttsEngine.resume()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
            ACTION_STOP -> {
                handleStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStop() {
        // Stop TTS engine playback first
        ReaderToMeApp.instance.ttsEngine.stop()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updatePlaybackState(state: Int) {
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun createNotification(bookTitle: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, TtsPlaybackService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TtsPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ReaderToMeApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OnDeviceReaderAI")
            .setContentText("Reading: $bookTitle")
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_headphones, "Pause", pausePendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.readertomeai.tts.START"
        const val ACTION_PAUSE = "com.readertomeai.tts.PAUSE"
        const val ACTION_RESUME = "com.readertomeai.tts.RESUME"
        const val ACTION_STOP = "com.readertomeai.tts.STOP"
        const val EXTRA_BOOK_TITLE = "book_title"
    }
}
