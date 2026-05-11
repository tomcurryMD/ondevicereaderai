package com.readertomeai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.readertomeai.data.model.AvailableVoices
import com.readertomeai.data.model.VoiceModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

enum class TtsState {
    IDLE, LOADING, SPEAKING, PAUSED
}

data class TtsSpeakingProgress(
    val sentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val currentSentenceStart: Int = 0,
    val currentSentenceEnd: Int = 0
)

class TtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var speakJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state

    private val _progress = MutableStateFlow(TtsSpeakingProgress())
    val progress: StateFlow<TtsSpeakingProgress> = _progress

    private val _currentVoice = MutableStateFlow<VoiceModel?>(null)
    val currentVoice: StateFlow<VoiceModel?> = _currentVoice

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    var speed: Float = 1.0f

    @Volatile
    private var isPaused = false

    @Volatile
    private var isCancelled = false

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioFocusRequest by lazy {
        android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (_state.value == TtsState.PAUSED) resume()
                    }
                }
            }
            .build()
    }

    private val modelsDir: File
        get() = File(context.filesDir, "tts_models").apply { mkdirs() }

    private fun findVoiceModelFile(voiceDir: File): File? =
        voiceDir.takeIf { it.exists() }?.walkTopDown()?.firstOrNull {
            it.isFile && it.extension == "onnx"
        }

    private fun findVoiceTokensFile(voiceDir: File): File? =
        voiceDir.takeIf { it.exists() }?.walkTopDown()?.firstOrNull {
            it.isFile && it.name == "tokens.txt"
        }

    private fun findEspeakDataDir(): File? =
        modelsDir.walkTopDown().firstOrNull {
            it.isDirectory && it.name == "espeak-ng-data" && !it.listFiles().isNullOrEmpty()
        }

    // ── Voice management ────────────────────────────────────────────

    fun getDownloadedVoices(): List<VoiceModel> {
        return AvailableVoices.voices.map { voice ->
            val voiceDir = File(modelsDir, voice.id)
            // Archives may unpack into nested folders, so search recursively
            val modelFile = findVoiceModelFile(voiceDir)
            val tokensFile = findVoiceTokensFile(voiceDir)
            // espeak-ng-data may also be nested
            val espeakDir = findEspeakDataDir()

            if (modelFile != null && tokensFile != null && espeakDir != null) {
                voice.copy(
                    isDownloaded = true,
                    localModelPath = modelFile.absolutePath,
                    localTokensPath = tokensFile.absolutePath,
                    localDataDir = espeakDir.absolutePath
                )
            } else {
                voice
            }
        }
    }

    suspend fun downloadVoice(voice: VoiceModel): Boolean = withContext(Dispatchers.IO) {
        try {
            _downloadProgress.value = 0f

            // Step 1: Download espeak-ng-data if not present (shared across all voices)
            if (findEspeakDataDir() == null) {
                downloadAndExtractTarBz2(
                    voice.espeakDataUrl,
                    modelsDir
                ) { progress ->
                    _downloadProgress.value = progress * 0.3f
                }
            } else {
                _downloadProgress.value = 0.3f
            }

            // Step 2: Download voice model archive
            val voiceDir = File(modelsDir, voice.id).apply { mkdirs() }
            downloadAndExtractTarBz2(
                voice.modelUrl,
                voiceDir
            ) { progress ->
                _downloadProgress.value = 0.3f + progress * 0.7f
            }

            _downloadProgress.value = 1f
            delay(500)
            _downloadProgress.value = null

            // Verify files exist
            val modelFile = findVoiceModelFile(voiceDir)
            val tokensFile = findVoiceTokensFile(voiceDir)
            val espeakDir = findEspeakDataDir()
            modelFile != null && tokensFile != null && espeakDir != null
        } catch (e: Exception) {
            e.printStackTrace()
            _downloadProgress.value = null
            false
        }
    }

    private fun downloadAndExtractTarBz2(
        urlString: String,
        destDir: File,
        onProgress: (Float) -> Unit
    ) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            throw IOException("Failed to download TTS model: HTTP $responseCode")
        }

        val totalSize = connection.contentLength
        var downloadedSize = 0

        // Download to temp file first
        val tempFile = File.createTempFile("tts_download_", ".tar.bz2", context.cacheDir)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead
                        if (totalSize > 0) {
                            onProgress(downloadedSize.toFloat() / totalSize * 0.8f)
                        }
                    }
                }
            }

            // Extract tar.bz2
            destDir.mkdirs()
            val canonicalDestDir = destDir.canonicalFile
            FileInputStream(tempFile).use { fis ->
                BZip2CompressorInputStream(fis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val outFile = safeArchiveOutput(canonicalDestDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    tar.copyTo(fos)
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
            onProgress(1f)
        } finally {
            connection.disconnect()
            tempFile.delete()
        }
    }

    private fun safeArchiveOutput(destDir: File, entryName: String): File {
        val outFile = File(destDir, entryName).canonicalFile
        val destPath = destDir.canonicalPath
        if (outFile.path != destPath && !outFile.path.startsWith(destPath + File.separator)) {
            throw IOException("Blocked unsafe archive entry: $entryName")
        }
        return outFile
    }

    fun deleteVoice(voice: VoiceModel) {
        val voiceDir = File(modelsDir, voice.id)
        voiceDir.deleteRecursively()
    }

    // ── Voice initialization ────────────────────────────────────────

    fun initializeVoice(voiceId: String): Boolean {
        val voices = getDownloadedVoices()
        val voice = voices.find { it.id == voiceId && it.isDownloaded } ?: return false

        return try {
            _state.value = TtsState.LOADING

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voice.localModelPath!!,
                        tokens = voice.localTokensPath!!,
                        dataDir = voice.localDataDir!!,
                        lengthScale = 1.0f / speed
                    ),
                    numThreads = 2,
                    debug = false
                )
            )

            tts?.release()
            tts = OfflineTts(context.assets, config)
            _currentVoice.value = voice
            _state.value = TtsState.IDLE
            true
        } catch (e: Exception) {
            e.printStackTrace()
            _state.value = TtsState.IDLE
            false
        }
    }

    // ── Playback ────────────────────────────────────────────────────

    fun speak(
        text: String,
        onSentenceStart: ((sentenceIndex: Int, startChar: Int, endChar: Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        stop()

        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) return

        // Request audio focus
        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        isPaused = false
        isCancelled = false
        _state.value = TtsState.SPEAKING

        speakJob = scope.launch {
            try {
                var charOffset = 0
                for ((index, sentence) in sentences.withIndex()) {
                    if (isCancelled || !isActive) break

                    // Wait while paused
                    while (isPaused && !isCancelled && isActive) {
                        delay(100)
                    }
                    if (isCancelled || !isActive) break

                    val sentenceStart = text.indexOf(sentence, charOffset)
                    val sentenceEnd = sentenceStart + sentence.length

                    _progress.value = TtsSpeakingProgress(
                        sentenceIndex = index,
                        totalSentences = sentences.size,
                        currentSentenceStart = sentenceStart,
                        currentSentenceEnd = sentenceEnd
                    )

                    withContext(Dispatchers.Main) {
                        onSentenceStart?.invoke(index, sentenceStart, sentenceEnd)
                    }

                    // Generate audio for this sentence
                    val currentTts = tts ?: break
                    val audio = currentTts.generate(
                        text = sentence,
                        sid = 0,
                        speed = speed
                    )

                    if (isCancelled || !isActive) break

                    // Play audio and wait for completion
                    playAudioBlocking(audio.samples, audio.sampleRate)

                    charOffset = sentenceEnd
                }
            } finally {
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
                withContext(Dispatchers.Main) {
                    if (!isCancelled) {
                        _state.value = TtsState.IDLE
                        onComplete?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Plays audio samples and blocks until playback is complete or cancelled.
     * Properly handles pause/resume by monitoring AudioTrack state instead of using delay.
     */
    private suspend fun playAudioBlocking(samples: FloatArray, sampleRate: Int) {
        withContext(Dispatchers.IO) {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, samples.size * 4))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            try {
                audioTrack = track
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()

                // Poll playback position instead of using a fixed delay
                val totalFrames = samples.size
                while (!isCancelled && isActive) {
                    val pos = track.playbackHeadPosition
                    if (pos >= totalFrames) break

                    // Handle pause: AudioTrack.pause() stops advancing head position
                    // We just wait until resumed or cancelled
                    if (isPaused) {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.pause()
                        }
                    } else {
                        if (track.playState == AudioTrack.PLAYSTATE_PAUSED) {
                            track.play()
                        }
                    }
                    delay(50)
                }

                track.stop()
            } catch (e: Exception) {
                // IllegalStateException if track was released
            } finally {
                try {
                    track.release()
                } catch (_: Exception) {}
                audioTrack = null
            }
        }
    }

    fun pause() {
        isPaused = true
        _state.value = TtsState.PAUSED
        // AudioTrack pause is handled in playAudioBlocking loop
    }

    fun resume() {
        isPaused = false
        _state.value = TtsState.SPEAKING
        // AudioTrack resume is handled in playAudioBlocking loop
    }

    fun stop() {
        isCancelled = true
        speakJob?.cancel()
        speakJob = null
        isPaused = false

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        audioManager.abandonAudioFocusRequest(audioFocusRequest)

        _state.value = TtsState.IDLE
        _progress.value = TtsSpeakingProgress()
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
        scope.cancel()
    }

    // ── Sentence splitting ──────────────────────────────────────────

    private fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val sentences = mutableListOf<String>()
        val pattern = Regex("""(?<=[.!?])\s+(?=[A-Z"'“”])""")
        val parts = pattern.split(text)

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                if (trimmed.length > 200) {
                    val subParts = trimmed.split(Regex("""(?<=[,;:])\s+"""))
                    val buffer = StringBuilder()
                    for (sub in subParts) {
                        if (buffer.length + sub.length > 200 && buffer.isNotEmpty()) {
                            sentences.add(buffer.toString().trim())
                            buffer.clear()
                        }
                        if (buffer.isNotEmpty()) buffer.append(" ")
                        buffer.append(sub)
                    }
                    if (buffer.isNotEmpty()) sentences.add(buffer.toString().trim())
                } else {
                    sentences.add(trimmed)
                }
            }
        }
        return sentences
    }
}
