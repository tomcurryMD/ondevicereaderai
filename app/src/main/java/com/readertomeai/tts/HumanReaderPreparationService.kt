package com.readertomeai.tts

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.readertomeai.MainActivity
import com.readertomeai.R
import com.readertomeai.ReaderToMeApp
import com.readertomeai.data.model.AvailableVoices
import com.readertomeai.data.model.Book
import com.readertomeai.data.model.VoiceModel
import com.readertomeai.epub.DocumentParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class HumanReaderPreparationProgress(
    val bookId: Long? = null,
    val title: String = "",
    val isRunning: Boolean = false,
    val requiredCount: Int = 0,
    val readyCount: Int = 0,
    val currentChapter: Int? = null,
    val currentChapterProgress: Float? = null,
    val message: String? = null
)

class HumanReaderPreparationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var prepJob: Job? = null
    private var currentBookId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val request = readRequest(intent) ?: restoreRequest()
                if (request == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                saveRequest(request)
                currentBookId = request.bookId
                acquireWakeLock()
                updateProgress(
                    HumanReaderPreparationProgress(
                        bookId = request.bookId,
                        title = request.bookTitle,
                        isRunning = true,
                        message = "Preparing full Human Reader book. This can run overnight."
                    )
                )
                startForeground(
                    ReaderToMeApp.HUMAN_READER_NOTIFICATION_ID,
                    createNotification(progress.value)
                )
                startPreparation(request)
            }
            ACTION_STOP -> {
                cancelPreparation(clearRequest = true)
            }
        }
        return START_STICKY
    }

    private fun startPreparation(request: PreparationRequest) {
        val existing = prepJob
        if (existing?.isActive == true && currentBookId == request.bookId) {
            notifyProgress()
            return
        }

        existing?.cancel()
        prepJob = serviceScope.launch {
            runPreparation(request)
        }
    }

    private suspend fun runPreparation(request: PreparationRequest) {
        val parser = DocumentParser(this)
        try {
            val app = ReaderToMeApp.instance
            val book = app.bookRepository.getBook(request.bookId)
            if (book == null) {
                finishWithMessage(request, "Could not find this book for Human Reader prep.")
                return
            }

            val opened = parser.openDocument(book.filePath)
            if (!opened) {
                finishWithMessage(request, "Could not open this book for Human Reader prep.")
                return
            }

            val totalChapters = parser.getChapterCount()
            val requiredChapters = (0 until totalChapters).filter {
                parser.getPlainTextForChapter(it).isNotBlank()
            }
            if (requiredChapters.isEmpty()) {
                finishWithMessage(request, "This book has no readable chapters for Human Reader.")
                return
            }

            val voice = selectedHumanVoice(request.voiceId)
            cleanupHumanReaderTempFiles(book.id, voice.id)

            app.settingsRepository.migrateTtsDefaultsIfNeeded()
            app.ttsEngine.speed = app.settingsRepository.ttsSpeed.first()

            if (!ensureHumanReaderVoiceReady(voice, request, requiredChapters.size)) {
                finishWithMessage(request, "Could not prepare Human Reader model. Check connection and free storage.")
                return
            }

            val pendingChapters = requiredChapters.filterNot {
                humanReaderChapterReady(book.id, voice.id, it, 0)
            }
            val initialReady = requiredChapters.size - pendingChapters.size
            updateProgress(
                progress.value.copy(
                    bookId = book.id,
                    title = book.title,
                    isRunning = true,
                    requiredCount = requiredChapters.size,
                    readyCount = initialReady,
                    currentChapter = pendingChapters.firstOrNull(),
                    currentChapterProgress = null,
                    message = if (pendingChapters.isEmpty()) {
                        "Human Reader is ready."
                    } else {
                        "Preparing full Human Reader book: $initialReady/${requiredChapters.size} chapters ready. This can run overnight."
                    }
                )
            )

            if (pendingChapters.isEmpty()) {
                clearSavedRequest()
                stopSelf()
                return
            }

            val estimatedBytes = estimatePendingHumanReaderBytes(book, voice, pendingChapters, parser)
            if (!hasEnoughHumanReaderStorage(estimatedBytes)) {
                finishWithMessage(request, humanReaderStorageMessage(estimatedBytes, requiredChapters.size, initialReady))
                return
            }

            var readyCount = initialReady
            for ((index, chapterIndex) in pendingChapters.withIndex()) {
                currentCoroutineContext().ensureActive()
                updateProgress(
                    progress.value.copy(
                        bookId = book.id,
                        title = book.title,
                        isRunning = true,
                        requiredCount = requiredChapters.size,
                        readyCount = readyCount,
                        currentChapter = chapterIndex,
                        currentChapterProgress = 0f,
                        message = "Preparing full Human Reader book: $readyCount/${requiredChapters.size} chapters ready. Working ${index + 1}/${pendingChapters.size}."
                    )
                )

                val rendered = prepareHumanReaderChapter(
                    book = book,
                    voice = voice,
                    chapterIndex = chapterIndex,
                    parser = parser,
                    requiredCount = requiredChapters.size
                )
                if (!rendered) {
                    finishWithMessage(
                        request,
                        "Human Reader stopped at $readyCount/${requiredChapters.size} chapters. Check free storage, then resume the full-book download."
                    )
                    return
                }
                readyCount += 1
                updateProgress(
                    progress.value.copy(
                        readyCount = readyCount,
                        currentChapterProgress = null,
                        message = "Preparing full Human Reader book: $readyCount/${requiredChapters.size} chapters ready."
                    )
                )
            }

            updateProgress(
                progress.value.copy(
                    isRunning = false,
                    readyCount = requiredChapters.size,
                    currentChapter = null,
                    currentChapterProgress = null,
                    message = "Human Reader is ready."
                )
            )
            clearSavedRequest()
            stopSelf()
        } catch (_: CancellationException) {
            updateProgress(
                progress.value.copy(
                    isRunning = false,
                    currentChapter = null,
                    currentChapterProgress = null,
                    message = "Human Reader preparation stopped."
                )
            )
        } catch (e: Throwable) {
            updateProgress(
                progress.value.copy(
                    isRunning = false,
                    currentChapter = null,
                    currentChapterProgress = null,
                    message = "Human Reader preparation stopped unexpectedly. Resume the full-book download."
                )
            )
        } finally {
            parser.close()
            clearSavedRequest()
            releaseWakeLock()
            notifyProgress()
            prepJob = null
        }
    }

    private suspend fun ensureHumanReaderVoiceReady(
        voice: VoiceModel,
        request: PreparationRequest,
        requiredCount: Int
    ): Boolean {
        val app = ReaderToMeApp.instance
        if (app.ttsEngine.currentVoice.value?.id == voice.id) return true

        val downloaded = app.ttsEngine.getDownloadedVoices().any {
            it.id == voice.id && it.isDownloaded
        }

        updateProgress(
            progress.value.copy(
                bookId = request.bookId,
                title = request.bookTitle,
                isRunning = true,
                requiredCount = requiredCount,
                message = if (downloaded) {
                    "Starting Human Reader model."
                } else {
                    "Downloading Human Reader model. This is a one-time download."
                }
            )
        )

        return if (downloaded) {
            app.ttsEngine.initializeVoice(voice.id)
        } else {
            app.ttsEngine.downloadAndInitializeVoice(voice)
        }
    }

    private suspend fun prepareHumanReaderChapter(
        book: Book,
        voice: VoiceModel,
        chapterIndex: Int,
        parser: DocumentParser,
        requiredCount: Int
    ): Boolean {
        val files = humanReaderFiles(book.id, voice.id, chapterIndex, 0)
        if (isHumanReaderCacheReady(files)) return true
        files.tempWav.delete()
        files.tempMarks.delete()

        val text = parser.getPlainTextForChapter(chapterIndex)
        if (text.isBlank()) return false

        val estimatedBytes = estimateHumanReaderAudioBytes(text)
        if (!hasEnoughHumanReaderStorage(estimatedBytes)) return false

        var lastProgress = -1f
        val rendered = ReaderToMeApp.instance.ttsEngine.renderCurrentVoiceToWav(
            text = text,
            wavFile = files.tempWav,
            marksFile = files.tempMarks
        ) { chapterProgress ->
            if (chapterProgress >= 1f || chapterProgress - lastProgress >= 0.02f) {
                lastProgress = chapterProgress
                val ready = progress.value.readyCount
                updateProgress(
                    progress.value.copy(
                        currentChapter = chapterIndex,
                        currentChapterProgress = chapterProgress,
                        message = "Preparing full Human Reader book: $ready/$requiredCount chapters ready. Current chapter ${(chapterProgress * 100).toInt()}%."
                    )
                )
            }
        }

        val committed = rendered && commitHumanReaderCache(files)
        if (!committed) {
            files.tempWav.delete()
            files.tempMarks.delete()
        }
        return committed
    }

    private fun updateProgress(newProgress: HumanReaderPreparationProgress) {
        _progress.value = newProgress
        notifyProgress()
    }

    private fun notifyProgress() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            ReaderToMeApp.HUMAN_READER_NOTIFICATION_ID,
            createNotification(progress.value)
        )
    }

    private fun finishWithMessage(request: PreparationRequest, message: String) {
        updateProgress(
            progress.value.copy(
                bookId = request.bookId,
                title = request.bookTitle,
                isRunning = false,
                currentChapter = null,
                currentChapterProgress = null,
                message = message
            )
        )
        clearSavedRequest()
        stopSelf()
    }

    private fun cancelPreparation(clearRequest: Boolean) {
        prepJob?.cancel()
        prepJob = null
        if (clearRequest) clearSavedRequest()
        updateProgress(
            progress.value.copy(
                isRunning = false,
                currentChapter = null,
                currentChapterProgress = null,
                message = "Human Reader preparation stopped."
            )
        )
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing?.isHeld == true) {
            existing.acquire(WAKE_LOCK_TIMEOUT_MS)
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ReaderToMeAI:HumanReaderPreparation"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotification(state: HumanReaderPreparationProgress): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val content = state.message ?: "Full-book prep running for ${state.title.ifBlank { "book" }}"
        val builder = NotificationCompat.Builder(this, ReaderToMeApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Preparing Human Reader")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentIntent(pendingIntent)
            .setOngoing(state.isRunning)
            .setSilent(true)

        if (state.requiredCount > 0) {
            val progressValue = (state.readyCount + (state.currentChapterProgress ?: 0f))
                .coerceIn(0f, state.requiredCount.toFloat())
            builder.setProgress(
                state.requiredCount * 100,
                (progressValue * 100).toInt(),
                false
            )
        } else if (state.isRunning) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun selectedHumanVoice(voiceId: String?): VoiceModel {
        val selectedId = voiceId ?: AvailableVoices.HUMAN_READER_VOICE_ID
        return AvailableVoices.voices.find { it.id == selectedId }
            ?: AvailableVoices.voices.first { it.id == AvailableVoices.HUMAN_READER_VOICE_ID }
    }

    private data class HumanReaderFiles(
        val wav: File,
        val marks: File,
        val tempWav: File,
        val tempMarks: File
    )

    private fun humanReaderFiles(
        bookId: Long,
        voiceId: String,
        chapterIndex: Int,
        startOffset: Int
    ): HumanReaderFiles {
        val safeVoiceId = voiceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val dir = humanReaderCacheDir(bookId, safeVoiceId)
        val suffix = if (startOffset <= 0) "full" else "from_$startOffset"
        val wav = File(dir, "chapter_${chapterIndex}_$suffix.wav")
        val marks = File(dir, "chapter_${chapterIndex}_$suffix.marks")
        return HumanReaderFiles(
            wav = wav,
            marks = marks,
            tempWav = File(dir, "${wav.name}.tmp"),
            tempMarks = File(dir, "${marks.name}.tmp")
        )
    }

    private fun humanReaderCacheDir(bookId: Long, safeVoiceId: String): File =
        File(filesDir, "human_reader/book_$bookId/$safeVoiceId")

    private fun humanReaderChapterReady(
        bookId: Long,
        voiceId: String,
        chapterIndex: Int,
        startOffset: Int
    ): Boolean = isHumanReaderCacheReady(humanReaderFiles(bookId, voiceId, chapterIndex, startOffset))

    private fun isHumanReaderCacheReady(files: HumanReaderFiles): Boolean {
        val ready = isValidWavFile(files.wav) && isValidMarksFile(files.marks)
        if (!ready && (files.wav.exists() || files.marks.exists())) {
            files.wav.delete()
            files.marks.delete()
        }
        return ready
    }

    private fun isValidWavFile(file: File): Boolean {
        if (!file.exists() || file.length() <= MIN_HUMAN_READER_WAV_BYTES) return false
        return ReaderToMeApp.instance.ttsEngine.isPlayableRenderedWav(file)
    }

    private fun isValidMarksFile(file: File): Boolean =
        file.exists() && file.length() > 0L &&
            runCatching {
                ReaderToMeApp.instance.ttsEngine.readRenderedMarks(file).isNotEmpty()
            }.getOrDefault(false)

    private fun commitHumanReaderCache(files: HumanReaderFiles): Boolean {
        if (!isValidWavFile(files.tempWav) || !isValidMarksFile(files.tempMarks)) return false

        files.wav.parentFile?.mkdirs()
        files.wav.delete()
        files.marks.delete()

        val wavMoved = files.tempWav.renameTo(files.wav) || runCatching {
            files.tempWav.copyTo(files.wav, overwrite = true)
            files.tempWav.delete()
            true
        }.getOrDefault(false)
        val marksMoved = files.tempMarks.renameTo(files.marks) || runCatching {
            files.tempMarks.copyTo(files.marks, overwrite = true)
            files.tempMarks.delete()
            true
        }.getOrDefault(false)

        val committed = wavMoved && marksMoved && isHumanReaderCacheReady(files)
        if (!committed) {
            files.wav.delete()
            files.marks.delete()
        }
        return committed
    }

    private fun cleanupHumanReaderTempFiles(bookId: Long, voiceId: String) {
        val safeVoiceId = voiceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        humanReaderCacheDir(bookId, safeVoiceId)
            .listFiles { file -> file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }

    private fun estimatePendingHumanReaderBytes(
        book: Book,
        voice: VoiceModel,
        chapters: List<Int>,
        parser: DocumentParser
    ): Long = chapters.sumOf { chapterIndex ->
        if (humanReaderChapterReady(book.id, voice.id, chapterIndex, 0)) {
            0L
        } else {
            estimateHumanReaderAudioBytes(parser.getPlainTextForChapter(chapterIndex))
        }
    }

    private fun estimateHumanReaderAudioBytes(text: String): Long {
        val words = text.trim().split(WHITESPACE).count { it.isNotBlank() }.coerceAtLeast(1)
        return words * HUMAN_READER_ESTIMATED_BYTES_PER_WORD
    }

    private fun hasEnoughHumanReaderStorage(estimatedBytes: Long): Boolean {
        val neededBytes = estimatedBytes + MIN_HUMAN_READER_CACHE_FREE_BYTES
        return filesDir.usableSpace > neededBytes
    }

    private fun humanReaderStorageMessage(estimatedBytes: Long, requiredCount: Int, readyCount: Int): String {
        val neededMb = bytesToMb(estimatedBytes + MIN_HUMAN_READER_CACHE_FREE_BYTES)
        val freeMb = bytesToMb(filesDir.usableSpace)
        return "Human Reader needs about ${neededMb} MB free to finish this book. Free space now: ${freeMb} MB. Ready: $readyCount/$requiredCount chapters."
    }

    private fun bytesToMb(bytes: Long): Long =
        (bytes / (1024L * 1024L)).coerceAtLeast(1L)

    private fun readRequest(intent: Intent?): PreparationRequest? {
        val bookId = intent?.getLongExtra(EXTRA_BOOK_ID, -1L) ?: -1L
        if (bookId <= 0L) return null
        return PreparationRequest(
            bookId = bookId,
            bookTitle = intent?.getStringExtra(EXTRA_BOOK_TITLE).orEmpty(),
            voiceId = intent?.getStringExtra(EXTRA_VOICE_ID)
        )
    }

    private fun saveRequest(request: PreparationRequest) {
        val json = JSONObject()
            .put("bookId", request.bookId)
            .put("bookTitle", request.bookTitle)
            .put("voiceId", request.voiceId)
            .toString()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_ACTIVE_REQUEST, json)
            .apply()
    }

    private fun restoreRequest(): PreparationRequest? {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_ACTIVE_REQUEST, null)
            ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PreparationRequest(
                bookId = json.getLong("bookId"),
                bookTitle = json.optString("bookTitle"),
                voiceId = json.optString("voiceId").ifBlank { null }
            )
        }.getOrNull()
    }

    private fun clearSavedRequest() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(PREF_ACTIVE_REQUEST)
            .apply()
    }

    override fun onDestroy() {
        prepJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private data class PreparationRequest(
        val bookId: Long,
        val bookTitle: String,
        val voiceId: String?
    )

    companion object {
        const val ACTION_START = "com.readertomeai.tts.HUMAN_READER_START"
        const val ACTION_STOP = "com.readertomeai.tts.HUMAN_READER_STOP"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val EXTRA_VOICE_ID = "voice_id"

        private const val PREFS_NAME = "human_reader_preparation"
        private const val PREF_ACTIVE_REQUEST = "active_request"
        private const val WAKE_LOCK_TIMEOUT_MS = 18L * 60L * 60L * 1000L
        private const val MIN_HUMAN_READER_WAV_BYTES = 44L
        private const val MIN_HUMAN_READER_CACHE_FREE_BYTES = 300L * 1024L * 1024L
        private const val HUMAN_READER_ESTIMATED_BYTES_PER_WORD = 24_000L
        private val WHITESPACE = Regex("\\s+")

        private val _progress = MutableStateFlow(HumanReaderPreparationProgress())
        val progress: StateFlow<HumanReaderPreparationProgress> = _progress

        fun isPreparingAny(): Boolean = _progress.value.isRunning
    }
}
