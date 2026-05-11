package com.readertomeai.tts

import android.app.Notification
import android.app.NotificationManager
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
    private var currentBookTitle: String = "Reading aloud"
    private var isPlaying: Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "OnDeviceReaderAI").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPause() {
                    ReaderToMeApp.instance.ttsEngine.pause()
                    isPlaying = false
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    rebuildNotification()
                }
                override fun onPlay() {
                    ReaderToMeApp.instance.ttsEngine.resume()
                    isPlaying = true
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    rebuildNotification()
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
                currentBookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: "Reading aloud"
                isPlaying = true
                startForeground(ReaderToMeApp.NOTIFICATION_ID, createNotification())
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
            ACTION_PAUSE -> {
                ReaderToMeApp.instance.ttsEngine.pause()
                isPlaying = false
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                rebuildNotification()
            }
            ACTION_RESUME -> {
                ReaderToMeApp.instance.ttsEngine.resume()
                isPlaying = true
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                rebuildNotification()
            }
            ACTION_STOP -> {
                handleStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStop() {
        ReaderToMeApp.instance.ttsEngine.stop()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun rebuildNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ReaderToMeApp.NOTIFICATION_ID, createNotification())
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

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Swap Pause/Resume action based on current state
        val toggleAction = if (isPlaying) {
            val pauseIntent = Intent(this, TtsPlaybackService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePending = PendingIntent.getService(
                this, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(R.drawable.ic_headphones, "Pause", pausePending)
        } else {
            val resumeIntent = Intent(this, TtsPlaybackService::class.java).apply {
                action = ACTION_RESUME
            }
            val resumePending = PendingIntent.getService(
                this, 1, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(R.drawable.ic_headphones, "Resume", resumePending)
        }

        val stopIntent = Intent(this, TtsPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ReaderToMeApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OnDeviceReaderAI")
            .setContentText(if (isPlaying) "Reading: $currentBookTitle" else "Paused: $currentBookTitle")
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentIntent(pendingIntent)
            .addAction(toggleAction)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1))
            .setOngoing(isPlaying)
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
