package com.readertomeai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.readertomeai.data.model.AvailableVoices
import com.readertomeai.data.model.VoiceEngine
import com.readertomeai.data.model.VoiceModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class TtsState {
    IDLE, LOADING, SPEAKING, PAUSED
}

data class TtsSpeakingProgress(
    val sentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val currentSentenceStart: Int = 0,
    val currentSentenceEnd: Int = 0
)

data class RenderedTtsMark(
    val index: Int,
    val startChar: Int,
    val endChar: Int,
    val startMs: Long,
    val endMs: Long
)

private data class TtsUtterance(
    val index: Int,
    val text: String,
    val startChar: Int,
    val endChar: Int,
    val endsSentence: Boolean,
    val pauseAfterMs: Long
)

private data class TtsTextSegment(
    val text: String,
    val endsSentence: Boolean,
    val pauseAfterMs: Long = 0L
)

private data class GeneratedTtsAudio(
    val utterance: TtsUtterance,
    val samples: FloatArray,
    val sampleRate: Int
)

private const val DEFAULT_TTS_SPEED = 0.78f
private const val TTS_AHEAD_BUFFER_SIZE = 24
private const val INITIAL_TTS_PREFETCH_ITEMS = 2
private const val TTS_SENTENCE_PAUSE_MS = 240L
private const val TTS_PHRASE_PAUSE_MS = 45L
private const val MIN_TTS_FRAGMENT_WORDS = 8
private const val TARGET_TTS_FRAGMENT_WORDS = 14
private const val MAX_TTS_FRAGMENT_WORDS = 18
private const val MAX_TTS_CHUNK_WORDS = MAX_TTS_FRAGMENT_WORDS
private const val HUMAN_READER_TARGET_WORDS = 55
private const val HUMAN_READER_MAX_WORDS = 80
private const val BUNDLED_TTS_ASSET_DIR = "bundled_tts"

class TtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var speakJob: Job? = null
    private val bundledDefaultInstallMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val generatorDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TtsGenerator").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state

    private val _progress = MutableStateFlow(TtsSpeakingProgress())
    val progress: StateFlow<TtsSpeakingProgress> = _progress

    private val _currentVoice = MutableStateFlow<VoiceModel?>(null)
    val currentVoice: StateFlow<VoiceModel?> = _currentVoice

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    var speed: Float = DEFAULT_TTS_SPEED

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

    private fun findKokoroVoicesFile(voiceDir: File): File? =
        voiceDir.takeIf { it.exists() }?.walkTopDown()?.firstOrNull {
            it.isFile && it.name == "voices.bin"
        }

    private fun findKokoroLexicon(voiceDir: File): String? =
        voiceDir.takeIf { it.exists() }?.walkTopDown()
            ?.filter { it.isFile && it.name.startsWith("lexicon-") && it.extension == "txt" }
            ?.sortedByDescending { file ->
                when (file.name) {
                    "lexicon-us-en.txt" -> 3
                    "lexicon-gb-en.txt" -> 2
                    "lexicon-zh.txt" -> 1
                    else -> 0
                }
            }
            ?.take(2)
            ?.joinToString(",") { it.absolutePath }
            ?.takeIf { it.isNotBlank() }

    private fun findEspeakDataDir(): File? =
        modelsDir.walkTopDown().firstOrNull {
            it.isDirectory && it.name == "espeak-ng-data" && !it.listFiles().isNullOrEmpty()
        }

    // ── Voice management ────────────────────────────────────────────

    fun getDownloadedVoices(): List<VoiceModel> {
        return AvailableVoices.voices.map { voice ->
            val voiceDir = File(modelsDir, voice.id)
            val modelFile = findVoiceModelFile(voiceDir)
            val tokensFile = findVoiceTokensFile(voiceDir)
            val espeakDir = findEspeakDataDir()
            val voicesFile = findKokoroVoicesFile(voiceDir)
            val lexicon = findKokoroLexicon(voiceDir)

            if (voice.engine == VoiceEngine.PIPER_VITS && modelFile != null && tokensFile != null && espeakDir != null) {
                voice.copy(
                    isDownloaded = true,
                    localModelPath = modelFile.absolutePath,
                    localTokensPath = tokensFile.absolutePath,
                    localDataDir = espeakDir.absolutePath
                )
            } else if (voice.engine == VoiceEngine.KOKORO && modelFile != null && tokensFile != null && espeakDir != null && voicesFile != null) {
                voice.copy(
                    isDownloaded = true,
                    localModelPath = modelFile.absolutePath,
                    localTokensPath = tokensFile.absolutePath,
                    localDataDir = espeakDir.absolutePath,
                    localVoicesPath = voicesFile.absolutePath,
                    localLexiconPath = lexicon
                )
            } else {
                voice
            }
        }
    }

    suspend fun ensureBundledDefaultVoiceInstalled(): Boolean = bundledDefaultInstallMutex.withLock {
        withContext(Dispatchers.IO) {
            val defaultVoice = AvailableVoices.voices.find { it.id == AvailableVoices.DEFAULT_VOICE_ID }
                ?: return@withContext false
            if (getDownloadedVoices().any { it.id == defaultVoice.id && it.isDownloaded }) {
                return@withContext true
            }

            val assetPath = "$BUNDLED_TTS_ASSET_DIR/${defaultVoice.id}.tar.bz2"
            if (!assetExists(assetPath)) {
                return@withContext false
            }

            val voiceDir = File(modelsDir, defaultVoice.id)
            try {
                _downloadProgress.value = 0f
                voiceDir.deleteRecursively()
                voiceDir.mkdirs()
                context.assets.open(assetPath).use { input ->
                    extractTarBz2(input, voiceDir)
                }
                _downloadProgress.value = 1f
                val installed = getDownloadedVoices().any { it.id == defaultVoice.id && it.isDownloaded }
                _downloadProgress.value = null
                installed
            } catch (e: Exception) {
                e.printStackTrace()
                voiceDir.deleteRecursively()
                _downloadProgress.value = null
                false
            }
        }
    }

    suspend fun downloadVoice(voice: VoiceModel): Boolean = withContext(Dispatchers.IO) {
        try {
            if (voice.id == AvailableVoices.DEFAULT_VOICE_ID && ensureBundledDefaultVoiceInstalled()) {
                return@withContext true
            }

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
            val voicesFile = findKokoroVoicesFile(voiceDir)
            if (voice.engine == VoiceEngine.KOKORO) {
                modelFile != null && tokensFile != null && espeakDir != null && voicesFile != null
            } else {
                modelFile != null && tokensFile != null && espeakDir != null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _downloadProgress.value = null
            false
        }
    }

    suspend fun downloadAndInitializeVoice(voice: VoiceModel): Boolean {
        _state.value = TtsState.LOADING
        val downloaded = downloadVoice(voice)
        if (!downloaded) {
            _state.value = TtsState.IDLE
            return false
        }
        return initializeVoice(voice.id).also { initialized ->
            if (!initialized) {
                _state.value = TtsState.IDLE
            }
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
            FileInputStream(tempFile).use { fis ->
                extractTarBz2(fis, destDir)
            }
            onProgress(1f)
        } finally {
            connection.disconnect()
            tempFile.delete()
        }
    }

    private fun extractTarBz2(input: InputStream, destDir: File) {
        destDir.mkdirs()
        val canonicalDestDir = destDir.canonicalFile
        BZip2CompressorInputStream(input).use { bzis ->
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

    private fun assetExists(assetPath: String): Boolean =
        try {
            context.assets.open(assetPath).use { }
            true
        } catch (_: IOException) {
            false
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

            val modelConfig = when (voice.engine) {
                VoiceEngine.PIPER_VITS -> OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voice.localModelPath!!,
                        tokens = voice.localTokensPath!!,
                        dataDir = voice.localDataDir!!,
                        lengthScale = 1.0f / speed
                    ),
                    numThreads = 2,
                    debug = false
                )
                VoiceEngine.KOKORO -> OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = voice.localModelPath!!,
                        voices = voice.localVoicesPath!!,
                        tokens = voice.localTokensPath!!,
                        dataDir = voice.localDataDir!!,
                        lexicon = voice.localLexiconPath.orEmpty(),
                        lengthScale = 1.0f / speed
                    ),
                    numThreads = 4,
                    debug = false
                )
            }

            val config = OfflineTtsConfig(model = modelConfig)

            tts?.release()
            tts = OfflineTts(null, config)
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

        val segments = splitIntoTtsSegments(text)
        if (segments.isEmpty()) return

        // Request audio focus
        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        isPaused = false
        isCancelled = false
        _state.value = TtsState.SPEAKING

        speakJob = scope.launch {
            val generationFailed = AtomicBoolean(false)
            var generatorJob: Job? = null
            val audioChannel = Channel<GeneratedTtsAudio>(capacity = TTS_AHEAD_BUFFER_SIZE)

            try {
                val currentTts = tts ?: return@launch
                val playbackSpeed = speed
                val utterances = buildUtterances(text, segments)

                generatorJob = launch(generatorDispatcher) {
                    try {
                        for (utterance in utterances) {
                            if (isCancelled || !isActive) break

                            val audio = currentTts.generate(
                                text = utterance.text,
                                sid = _currentVoice.value?.speakerId ?: 0,
                                speed = playbackSpeed
                            )

                            if (isCancelled || !isActive) break

                            audioChannel.send(
                                GeneratedTtsAudio(
                                    utterance = utterance,
                                    samples = audio.samples,
                                    sampleRate = audio.sampleRate
                                )
                            )
                        }
                    } catch (e: Exception) {
                        generationFailed.set(true)
                        e.printStackTrace()
                    } finally {
                        audioChannel.close()
                    }
                }

                val prefetchedAudio = ArrayDeque<GeneratedTtsAudio>()
                repeat(INITIAL_TTS_PREFETCH_ITEMS) {
                    val audio = audioChannel.receiveCatching().getOrNull() ?: return@repeat
                    prefetchedAudio.addLast(audio)
                }

                while (!isCancelled && isActive) {
                    val audio = if (prefetchedAudio.isNotEmpty()) {
                        prefetchedAudio.removeFirst()
                    } else {
                        audioChannel.receiveCatching().getOrNull() ?: break
                    }

                    while (isPaused && !isCancelled && isActive) {
                        delay(100)
                    }
                    if (isCancelled || !isActive) break

                    _progress.value = TtsSpeakingProgress(
                        sentenceIndex = audio.utterance.index,
                        totalSentences = utterances.size,
                        currentSentenceStart = audio.utterance.startChar,
                        currentSentenceEnd = audio.utterance.endChar
                    )

                    withContext(Dispatchers.Main) {
                        onSentenceStart?.invoke(
                            audio.utterance.index,
                            audio.utterance.startChar,
                            audio.utterance.endChar
                        )
                    }

                    if (isCancelled || !isActive) break

                    playAudioBlocking(audio.samples, audio.sampleRate)
                    if (audio.utterance.pauseAfterMs > 0 && audio.utterance.index < utterances.lastIndex) {
                        waitForPause(audio.utterance.pauseAfterMs)
                    }
                }
            } finally {
                generatorJob?.cancel()
                audioChannel.close()
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
                withContext(Dispatchers.Main) {
                    if (!isCancelled && !generationFailed.get()) {
                        _state.value = TtsState.IDLE
                        onComplete?.invoke()
                    }
                }
            }
        }
    }

    suspend fun renderCurrentVoiceToWav(
        text: String,
        wavFile: File,
        marksFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(generatorDispatcher) {
        val currentTts = tts ?: return@withContext false
        val voice = _currentVoice.value ?: return@withContext false
        val segments = splitIntoHumanReaderSegments(text)
        val utterances = buildUtterances(text, segments)
        if (utterances.isEmpty()) return@withContext false

        wavFile.parentFile?.mkdirs()
        marksFile.parentFile?.mkdirs()

        val marks = mutableListOf<RenderedTtsMark>()
        var sampleRate = 24_000
        var totalSamples = 0L

        try {
            FileOutputStream(wavFile).use { output ->
                writeWavHeader(output, sampleRate, 0)

                utterances.forEachIndexed { index, utterance ->
                    ensureActive()
                    val audio = currentTts.generate(
                        text = utterance.text,
                        sid = voice.speakerId,
                        speed = speed
                    )
                    sampleRate = audio.sampleRate

                    val startMs = samplesToMs(totalSamples, sampleRate)
                    writePcm16Samples(output, audio.samples)
                    totalSamples += audio.samples.size
                    val endMs = samplesToMs(totalSamples, sampleRate)

                    marks.add(
                        RenderedTtsMark(
                            index = utterance.index,
                            startChar = utterance.startChar,
                            endChar = utterance.endChar,
                            startMs = startMs,
                            endMs = endMs
                        )
                    )

                    if (utterance.pauseAfterMs > 0 && index < utterances.lastIndex) {
                        val pauseSamples = (sampleRate * utterance.pauseAfterMs / 1000L).toInt()
                        writeSilence(output, pauseSamples)
                        totalSamples += pauseSamples
                    }

                    onProgress((index + 1).toFloat() / utterances.size)
                }
            }

            patchWavHeader(wavFile, sampleRate, totalSamples)
            writeMarks(marksFile, marks)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            wavFile.delete()
            marksFile.delete()
            false
        }
    }

    fun playRenderedChapter(
        wavFile: File,
        marks: List<RenderedTtsMark>,
        onSentenceStart: ((sentenceIndex: Int, startChar: Int, endChar: Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        stop()
        if (!wavFile.exists()) return

        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        isPaused = false
        isCancelled = false
        _state.value = TtsState.SPEAKING

        val completed = AtomicBoolean(false)
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(wavFile.absolutePath)
            setOnCompletionListener { completed.set(true) }
            prepare()
            start()
        }
        mediaPlayer = player

        speakJob = scope.launch {
            var lastMarkIndex = -1
            try {
                while (!isCancelled && isActive && !completed.get()) {
                    val positionMs = player.currentPosition.toLong()
                    val mark = marks.lastOrNull { it.startMs <= positionMs }
                    if (mark != null && mark.index != lastMarkIndex) {
                        lastMarkIndex = mark.index
                        _progress.value = TtsSpeakingProgress(
                            sentenceIndex = mark.index,
                            totalSentences = marks.size,
                            currentSentenceStart = mark.startChar,
                            currentSentenceEnd = mark.endChar
                        )
                        withContext(Dispatchers.Main) {
                            onSentenceStart?.invoke(mark.index, mark.startChar, mark.endChar)
                        }
                    }
                    delay(100)
                }
            } finally {
                try {
                    player.release()
                } catch (_: Exception) {}
                mediaPlayer = null
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

    fun readRenderedMarks(marksFile: File): List<RenderedTtsMark> {
        if (!marksFile.exists()) return emptyList()
        return marksFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 5) return@mapNotNull null
            RenderedTtsMark(
                index = parts[0].toIntOrNull() ?: return@mapNotNull null,
                startChar = parts[1].toIntOrNull() ?: return@mapNotNull null,
                endChar = parts[2].toIntOrNull() ?: return@mapNotNull null,
                startMs = parts[3].toLongOrNull() ?: return@mapNotNull null,
                endMs = parts[4].toLongOrNull() ?: return@mapNotNull null
            )
        }
    }

    private fun buildUtterances(text: String, segments: List<TtsTextSegment>): List<TtsUtterance> {
        var charOffset = 0
        val utterances = mutableListOf<TtsUtterance>()

        for ((index, segment) in segments.withIndex()) {
            val foundStart = text.indexOf(segment.text, charOffset)
            val start = if (foundStart >= 0) foundStart else charOffset.coerceAtMost(text.length)
            val end = (start + segment.text.length).coerceAtMost(text.length)
            charOffset = end
            utterances.add(
                TtsUtterance(
                    index = index,
                    text = segment.text,
                    startChar = start,
                    endChar = end,
                    endsSentence = segment.endsSentence,
                    pauseAfterMs = segment.pauseAfterMs
                )
            )
        }
        return utterances
    }

    private suspend fun waitForPause(pauseMs: Long) {
        var elapsedMs = 0L
        while (!isCancelled && elapsedMs < pauseMs) {
            if (isPaused) {
                delay(100)
            } else {
                delay(20)
                elapsedMs += 20
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
                    delay(20)
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
        try {
            audioTrack?.pause()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {}
    }

    fun resume() {
        isPaused = false
        _state.value = TtsState.SPEAKING
        try {
            audioTrack?.play()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.start()
        } catch (_: Exception) {}
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
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        audioManager.abandonAudioFocusRequest(audioFocusRequest)

        _state.value = TtsState.IDLE
        _progress.value = TtsSpeakingProgress()
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
        generatorDispatcher.close()
        scope.cancel()
    }

    // ── Sentence splitting ──────────────────────────────────────────

    private fun splitIntoTtsSegments(text: String): List<TtsTextSegment> {
        if (text.isBlank()) return emptyList()

        val segments = mutableListOf<TtsTextSegment>()
        val sentencePattern = Regex("""[^.!?]+(?:[.!?]+["')\]]*)?""")
        val sentences = sentencePattern
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val parts = sentences.ifEmpty { listOf(text.trim()) }

        for (part in parts) {
            addSentenceFragments(part, segments)
        }
        return segments
    }

    private fun splitIntoHumanReaderSegments(text: String): List<TtsTextSegment> {
        if (text.isBlank()) return emptyList()

        val sentencePattern = Regex("""[^.!?]+(?:[.!?]+["')\]]*)?""")
        val sentences = sentencePattern
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { listOf(text.trim()) }

        val segments = mutableListOf<TtsTextSegment>()
        var pending = ""
        sentences.forEach { sentence ->
            val candidate = if (pending.isBlank()) sentence else "$pending $sentence"
            if (wordCount(candidate) <= HUMAN_READER_TARGET_WORDS) {
                pending = candidate
            } else {
                if (pending.isNotBlank()) addHumanReaderFragment(pending, segments)
                pending = sentence
            }
        }
        if (pending.isNotBlank()) addHumanReaderFragment(pending, segments)
        return segments
    }

    private fun addHumanReaderFragment(fragment: String, out: MutableList<TtsTextSegment>) {
        val words = wordsIn(fragment)
        if (words.size <= HUMAN_READER_MAX_WORDS) {
            out.add(TtsTextSegment(fragment, endsSentence = true, pauseAfterMs = TTS_SENTENCE_PAUSE_MS))
            return
        }

        var wordIndex = 0
        while (wordIndex < words.size) {
            val nextIndex = (wordIndex + HUMAN_READER_TARGET_WORDS).coerceAtMost(words.size)
            out.add(
                TtsTextSegment(
                    text = words.subList(wordIndex, nextIndex).joinToString(" "),
                    endsSentence = nextIndex == words.size,
                    pauseAfterMs = if (nextIndex == words.size) TTS_SENTENCE_PAUSE_MS else TTS_PHRASE_PAUSE_MS
                )
            )
            wordIndex = nextIndex
        }
    }

    private fun addSentenceFragments(sentence: String, out: MutableList<TtsTextSegment>) {
        val phraseParts = sentence
            .split(Regex("""(?<=[,;:])\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val mergedPhrases = mutableListOf<String>()
        var pending = ""
        for (part in phraseParts) {
            pending = if (pending.isEmpty()) part else "$pending $part"
            if (wordCount(pending) >= MIN_TTS_FRAGMENT_WORDS) {
                mergedPhrases.add(pending)
                pending = ""
            }
        }
        if (pending.isNotEmpty()) {
            if (mergedPhrases.isNotEmpty() && wordCount(pending) < MIN_TTS_FRAGMENT_WORDS) {
                val previous = mergedPhrases.removeAt(mergedPhrases.lastIndex)
                mergedPhrases.add("$previous $pending")
            } else {
                mergedPhrases.add(pending)
            }
        }

        for ((index, phrase) in mergedPhrases.withIndex()) {
            val endsSentence = index == mergedPhrases.lastIndex
            addTtsFragment(phrase, endsSentence, out)
        }
    }

    private fun addTtsFragment(
        fragment: String,
        endsSentence: Boolean,
        out: MutableList<TtsTextSegment>
    ) {
        val words = wordsIn(fragment)
        if (words.isEmpty()) return
        val pauseAfterMs = if (endsSentence) TTS_SENTENCE_PAUSE_MS else TTS_PHRASE_PAUSE_MS

        if (words.size <= MAX_TTS_FRAGMENT_WORDS) {
            out.add(TtsTextSegment(fragment, endsSentence = endsSentence, pauseAfterMs = pauseAfterMs))
            return
        }

        var wordIndex = 0
        while (wordIndex < words.size) {
            val remaining = words.size - wordIndex
            val take = when {
                remaining <= MAX_TTS_FRAGMENT_WORDS -> remaining
                remaining <= MAX_TTS_FRAGMENT_WORDS + MIN_TTS_FRAGMENT_WORDS -> (remaining + 1) / 2
                else -> TARGET_TTS_FRAGMENT_WORDS
            }
            val nextIndex = (wordIndex + take).coerceAtMost(words.size)
            val isLastGroup = nextIndex == words.size
            out.add(
                TtsTextSegment(
                    text = words.subList(wordIndex, nextIndex).joinToString(" "),
                    endsSentence = endsSentence && isLastGroup,
                    pauseAfterMs = if (isLastGroup) pauseAfterMs else TTS_PHRASE_PAUSE_MS
                )
            )
            wordIndex = nextIndex
        }
    }

    private fun wordCount(text: String): Int = wordsIn(text).size

    private fun wordsIn(text: String): List<String> =
        text.split(Regex("""\s+""")).filter { it.isNotBlank() }

    private fun splitIntoLegacyTtsSegments(text: String): List<TtsTextSegment> {
        if (text.isBlank()) return emptyList()

        val segments = mutableListOf<TtsTextSegment>()
        val pattern = Regex("""(?<=[.!?])\s+(?=[A-Z"'“”])""")
        val parts = pattern.split(text)

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                addLegacyTtsWordGroups(trimmed, segments)
            }
        }
        return segments
    }

    private fun addLegacyTtsWordGroups(sentence: String, out: MutableList<TtsTextSegment>) {
        val words = sentence.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return
        if (words.size <= MAX_TTS_CHUNK_WORDS) {
            out.add(TtsTextSegment(sentence, endsSentence = true))
            return
        }

        var wordIndex = 0
        while (wordIndex < words.size) {
            val remaining = words.size - wordIndex
            val take = when {
                remaining <= MAX_TTS_CHUNK_WORDS -> remaining
                remaining <= MAX_TTS_CHUNK_WORDS * 2 -> (remaining + 1) / 2
                else -> MAX_TTS_CHUNK_WORDS
            }
            val nextIndex = (wordIndex + take).coerceAtMost(words.size)
            out.add(
                TtsTextSegment(
                    text = words.subList(wordIndex, nextIndex).joinToString(" "),
                    endsSentence = nextIndex == words.size
                )
            )
            wordIndex = nextIndex
        }
    }

    private fun samplesToMs(samples: Long, sampleRate: Int): Long =
        if (sampleRate <= 0) 0 else samples * 1000L / sampleRate

    private fun writePcm16Samples(output: OutputStream, samples: FloatArray) {
        val buffer = ByteArray(samples.size * 2)
        var offset = 0
        samples.forEach { sample ->
            val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[offset++] = (pcm and 0xff).toByte()
            buffer[offset++] = ((pcm shr 8) and 0xff).toByte()
        }
        output.write(buffer)
    }

    private fun writeSilence(output: OutputStream, samples: Int) {
        output.write(ByteArray(samples * 2))
    }

    private fun writeMarks(file: File, marks: List<RenderedTtsMark>) {
        file.writeText(
            marks.joinToString("\n") {
                "${it.index}\t${it.startChar}\t${it.endChar}\t${it.startMs}\t${it.endMs}"
            }
        )
    }

    private fun writeWavHeader(output: OutputStream, sampleRate: Int, totalSamples: Long) {
        val dataBytes = (totalSamples * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val byteRate = sampleRate * 2
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, 36 + dataBytes)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, 16)
        writeShortLe(output, 1)
        writeShortLe(output, 1)
        writeIntLe(output, sampleRate)
        writeIntLe(output, byteRate)
        writeShortLe(output, 2)
        writeShortLe(output, 16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, dataBytes)
    }

    private fun patchWavHeader(file: File, sampleRate: Int, totalSamples: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataBytes = (totalSamples * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            raf.seek(4)
            raf.write(intLeBytes(36 + dataBytes))
            raf.seek(24)
            raf.write(intLeBytes(sampleRate))
            raf.seek(28)
            raf.write(intLeBytes(sampleRate * 2))
            raf.seek(40)
            raf.write(intLeBytes(dataBytes))
        }
    }

    private fun writeIntLe(output: OutputStream, value: Int) {
        output.write(intLeBytes(value))
    }

    private fun writeShortLe(output: OutputStream, value: Int) {
        output.write(byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte()))
    }

    private fun intLeBytes(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )

}
