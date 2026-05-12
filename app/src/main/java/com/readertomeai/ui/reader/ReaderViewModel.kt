package com.readertomeai.ui.reader

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readertomeai.ReaderToMeApp
import com.readertomeai.data.model.*
import com.readertomeai.epub.DocumentParser
import com.readertomeai.tts.TtsPlaybackService
import com.readertomeai.tts.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HumanReaderChapterStatus(
    val label: String,
    val progress: Float? = null
)

data class ReaderUiState(
    val book: Book? = null,
    val currentChapter: Int = 0,
    val chapterHtml: String = "",
    val chapterTitle: String = "",
    val totalChapters: Int = 0,
    val tableOfContents: List<TocEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showControls: Boolean = true,
    val showToc: Boolean = false,
    val showBookmarks: Boolean = false,
    val showSettings: Boolean = false,
    val fontSize: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val readingTheme: String = "light",
    val fontFamily: String = "default",
    val margins: Int = 16,
    val savedScrollPosition: Float = 0f,
    val selectedText: String = "",
    val selectedTextStart: Int = 0,
    val selectedTextEnd: Int = 0,
    val ttsStatusMessage: String? = null,
    val humanReaderStatuses: Map<Int, HumanReaderChapterStatus> = emptyMap()
)

class ReaderViewModel : ViewModel() {

    private val repo = ReaderToMeApp.instance.bookRepository
    private val settings = ReaderToMeApp.instance.settingsRepository
    val ttsEngine = ReaderToMeApp.instance.ttsEngine

    private val parser = DocumentParser(ReaderToMeApp.instance)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    val ttsState = ttsEngine.state
    val ttsProgress = ttsEngine.progress

    // Track last scroll position per chapter for bookmarks
    private var currentScrollProgress: Float = 0f
    private var ttsResumeChapter: Int = -1
    private var ttsResumeOffset: Int = 0
    private var selectedPlaybackStartPending: Boolean = false
    private var selectionResolveJob: Job? = null

    /**
     * Callback set by ReaderScreen to execute JS in the WebView.
     * Used for TTS sentence highlighting.
     */
    var evaluateJavascript: ((String) -> Unit)? = null

    init {
        viewModelScope.launch {
            combine(
                settings.fontSize,
                settings.lineSpacing,
                settings.readingTheme,
                settings.fontFamily,
                settings.margins
            ) { fontSize, lineSpacing, theme, font, margins ->
                _uiState.update {
                    it.copy(
                        fontSize = fontSize, lineSpacing = lineSpacing,
                        readingTheme = theme, fontFamily = font, margins = margins
                    )
                }
                val state = _uiState.value
                if (state.book != null) {
                    loadChapterHtml(state.currentChapter)
                }
            }.collect()
        }
    }

    fun loadBook(bookId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val book = repo.getBook(bookId)
            if (book == null) {
                _uiState.update { it.copy(isLoading = false, error = "Book not found") }
                return@launch
            }

            // Move heavy parsing to IO thread
            val opened = withContext(Dispatchers.IO) {
                parser.openDocument(book.filePath)
            }
            if (!opened) {
                _uiState.update { it.copy(isLoading = false, error = "Could not open this document. It may be corrupted or in an unsupported format.") }
                return@launch
            }

            val toc = withContext(Dispatchers.IO) { parser.getTableOfContents() }
            val totalChapters = parser.getChapterCount()
            val initialChapter = withContext(Dispatchers.IO) {
                findReadableChapter(book.currentChapter, totalChapters)
            }
            val initialPosition = if (initialChapter == book.currentChapter) book.currentPosition else 0f
            currentScrollProgress = initialPosition
            ttsResumeChapter = initialChapter
            ttsResumeOffset = 0

            _uiState.update {
                it.copy(
                    book = book,
                    totalChapters = totalChapters,
                    tableOfContents = toc,
                    currentChapter = initialChapter,
                    savedScrollPosition = initialPosition
                )
            }

            refreshHumanReaderStatuses(book.id, totalChapters)

            // Collect bookmarks
            viewModelScope.launch {
                repo.getBookmarks(bookId).collect { bookmarks ->
                    _uiState.update { it.copy(bookmarks = bookmarks) }
                }
            }

            // Collect highlights
            viewModelScope.launch {
                repo.getHighlights(bookId).collect { highlights ->
                    _uiState.update { it.copy(highlights = highlights) }
                }
            }

            // Initialize TTS
            viewModelScope.launch(Dispatchers.IO) {
                settings.migrateTtsDefaultsIfNeeded()
                ttsEngine.ensureBundledDefaultVoiceInstalled()
                settings.ttsSpeed.first().let { speed ->
                    ttsEngine.speed = speed
                }
                settings.selectedVoiceId.first().let { voiceId ->
                    ttsEngine.initializeVoice(voiceId)
                }
            }

            if (initialChapter != book.currentChapter) {
                saveProgress(initialChapter, initialPosition)
            }
            loadChapterHtml(initialChapter)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun findReadableChapter(startIndex: Int, totalChapters: Int): Int {
        if (totalChapters <= 0) return 0
        val boundedStart = startIndex.coerceIn(0, totalChapters - 1)

        for (index in boundedStart until totalChapters) {
            if (hasSubstantialReadableText(index)) return index
        }

        for (index in 0 until boundedStart) {
            if (hasSubstantialReadableText(index)) return index
        }

        for (index in boundedStart until totalChapters) {
            if (parser.getPlainTextForChapter(index).isNotBlank()) return index
        }

        for (index in 0 until boundedStart) {
            if (parser.getPlainTextForChapter(index).isNotBlank()) return index
        }

        return boundedStart
    }

    private fun hasSubstantialReadableText(chapterIndex: Int): Boolean =
        parser.getPlainTextForChapter(chapterIndex).length >= MIN_READABLE_CHAPTER_CHARS

    private fun loadChapterHtml(chapterIndex: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val html = withContext(Dispatchers.IO) {
                parser.getChapterHtmlForWebView(
                    chapterIndex, state.fontSize, state.lineSpacing,
                    state.readingTheme, state.fontFamily, state.margins
                )
            }
            val chapter = withContext(Dispatchers.IO) { parser.getChapterContent(chapterIndex) }

            _uiState.update {
                it.copy(
                    chapterHtml = html,
                    chapterTitle = chapter?.title ?: "Chapter ${chapterIndex + 1}",
                    currentChapter = chapterIndex
                )
            }
        }
    }

    fun goToChapter(index: Int) {
        if (index < 0 || index >= _uiState.value.totalChapters) return
        ttsEngine.stop()
        resetTtsResume()
        currentScrollProgress = 0f
        _uiState.update { it.copy(savedScrollPosition = 0f) }
        loadChapterHtml(index)
        saveProgress(index, 0f)
        _uiState.value.book?.let { refreshHumanReaderStatuses(it.id, _uiState.value.totalChapters) }
    }

    fun nextChapter() = goToChapter(_uiState.value.currentChapter + 1)
    fun previousChapter() = goToChapter(_uiState.value.currentChapter - 1)

    /** Navigate to a bookmark, restoring both chapter and scroll position */
    fun goToBookmark(bookmark: com.readertomeai.data.model.Bookmark) {
        if (bookmark.chapterIndex < 0 || bookmark.chapterIndex >= _uiState.value.totalChapters) return
        ttsEngine.stop()
        resetTtsResume()
        currentScrollProgress = bookmark.position
        _uiState.update { it.copy(savedScrollPosition = bookmark.position) }
        loadChapterHtml(bookmark.chapterIndex)
        saveProgress(bookmark.chapterIndex, bookmark.position)
        // Scroll to position after page loads via JS
        viewModelScope.launch {
            kotlinx.coroutines.delay(500) // wait for WebView to render
            evaluateJavascript?.invoke("scrollToProgress(${bookmark.position});")
        }
    }

    fun onScrollProgress(progress: Float) {
        currentScrollProgress = progress
        val state = _uiState.value
        val overallProgress = if (state.totalChapters > 0) {
            (state.currentChapter + progress) / state.totalChapters
        } else 0f
        saveProgress(state.currentChapter, progress, overallProgress)
    }

    fun onTextSelected(text: String, startOffset: Int, endOffset: Int) {
        val chapter = _uiState.value.currentChapter
        val safeStart = startOffset.coerceAtLeast(0)
        val safeEnd = endOffset.coerceAtLeast(safeStart)
        ttsResumeChapter = chapter
        ttsResumeOffset = safeStart
        selectedPlaybackStartPending = true
        _uiState.update {
            it.copy(
                selectedText = text,
                selectedTextStart = safeStart,
                selectedTextEnd = safeEnd,
                ttsStatusMessage = "Playback will start from selected text."
            )
        }
        selectionResolveJob?.cancel()
        selectionResolveJob = viewModelScope.launch {
            val resolvedRange = resolveSelectedTextRange(chapter, text, safeStart, safeEnd)
            val currentState = _uiState.value
            if (currentState.currentChapter == chapter && currentState.selectedText == text) {
                ttsResumeChapter = chapter
                ttsResumeOffset = resolvedRange.first
                _uiState.update {
                    it.copy(
                        selectedTextStart = resolvedRange.first,
                        selectedTextEnd = resolvedRange.second
                    )
                }
            }
        }
        clearStatusMessageSoon("Playback will start from selected text.")
    }

    private suspend fun resolveSelectedTextRange(
        chapter: Int,
        selectedText: String,
        approximateStart: Int,
        approximateEnd: Int
    ): Pair<Int, Int> {
        val chapterText = withContext(Dispatchers.IO) {
            parser.getPlainTextForChapter(chapter)
        }
        if (chapterText.isBlank()) return approximateStart to approximateEnd

        val boundedStart = approximateStart.coerceIn(0, chapterText.length)
        val normalizedSelection = normalizeSelectionText(selectedText)
        if (normalizedSelection.isBlank()) return boundedStart to boundedStart

        val resolvedStart = findClosestTextMatch(chapterText, normalizedSelection, boundedStart)
            ?: boundedStart
        val resolvedEnd = (resolvedStart + normalizedSelection.length).coerceIn(resolvedStart, chapterText.length)
        return resolvedStart to resolvedEnd
    }

    private fun normalizeSelectionText(text: String): String =
        text.replace(WHITESPACE, " ").trim()

    private fun findClosestTextMatch(text: String, selection: String, approximateStart: Int): Int? {
        val candidates = mutableListOf(selection)
        if (selection.length > 160) candidates.add(selection.take(160))
        if (selection.length > 80) candidates.add(selection.take(80))
        if (selection.length > 40) candidates.add(selection.take(40))

        candidates.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { candidate ->
                closestIndexOf(text, candidate, approximateStart, ignoreCase = false)?.let { return it }
                closestIndexOf(text, candidate, approximateStart, ignoreCase = true)?.let { return it }
            }

        return null
    }

    private fun closestIndexOf(
        text: String,
        needle: String,
        approximateStart: Int,
        ignoreCase: Boolean
    ): Int? {
        var bestIndex: Int? = null
        var bestDistance = Int.MAX_VALUE
        var index = text.indexOf(needle, 0, ignoreCase)
        while (index >= 0) {
            val distance = kotlin.math.abs(index - approximateStart)
            if (distance < bestDistance) {
                bestIndex = index
                bestDistance = distance
            }
            index = text.indexOf(needle, index + 1, ignoreCase)
        }
        return bestIndex
    }

    private fun clearStatusMessageSoon(message: String) {
        viewModelScope.launch {
            delay(3_000)
            _uiState.update {
                if (it.ttsStatusMessage == message) it.copy(ttsStatusMessage = null) else it
            }
        }
    }

    private fun saveProgress(chapter: Int, position: Float, overallProgress: Float? = null) {
        val book = _uiState.value.book ?: return
        val overall = overallProgress ?: if (_uiState.value.totalChapters > 0) {
            (chapter + position) / _uiState.value.totalChapters
        } else 0f
        viewModelScope.launch {
            repo.updateProgress(book.id, chapter, position, overall)
        }
    }

    fun toggleControls() { _uiState.update { it.copy(showControls = !it.showControls) } }
    fun showToc() { _uiState.update { it.copy(showToc = true) } }
    fun hideToc() { _uiState.update { it.copy(showToc = false) } }
    fun showBookmarks() { _uiState.update { it.copy(showBookmarks = true) } }
    fun hideBookmarks() { _uiState.update { it.copy(showBookmarks = false) } }
    fun showSettings() { _uiState.update { it.copy(showSettings = true) } }
    fun hideSettings() { _uiState.update { it.copy(showSettings = false) } }

    fun addBookmark(label: String = "") {
        val state = _uiState.value
        val book = state.book ?: return
        viewModelScope.launch {
            repo.addBookmark(
                Bookmark(
                    bookId = book.id,
                    chapterIndex = state.currentChapter,
                    position = currentScrollProgress, // save actual scroll position
                    label = label.ifBlank { "${state.chapterTitle} (${(currentScrollProgress * 100).toInt()}%)" }
                )
            )
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repo.removeBookmark(bookmark) }
    }

    fun addHighlight(color: String = "yellow") {
        val state = _uiState.value
        val book = state.book ?: return
        val text = state.selectedText
        if (text.isBlank()) return
        viewModelScope.launch {
            repo.addHighlight(
                Highlight(
                    bookId = book.id,
                    chapterIndex = state.currentChapter,
                    startOffset = state.selectedTextStart,
                    endOffset = state.selectedTextEnd,
                    text = text,
                    color = color
                )
            )
        }
    }

    fun removeHighlight(highlight: Highlight) {
        viewModelScope.launch { repo.removeHighlight(highlight) }
    }

    // â”€â”€ TTS Controls â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun playTts() {
        val state = _uiState.value
        val book = state.book ?: return

        viewModelScope.launch {
            if (settings.readerMode.first() == ReaderMode.HUMAN) {
                playHumanReaderTts(state, book)
                return@launch
            }

            settings.migrateTtsDefaultsIfNeeded()
            ttsEngine.speed = settings.ttsSpeed.first()
            val selectedVoiceId = settings.selectedVoiceId.first()
            if (ttsEngine.currentVoice.value?.id != selectedVoiceId) {
                val initialized = ensureVoiceReady()
                if (!initialized) {
                    return@launch
                }
            }
            _uiState.update { it.copy(error = null, ttsStatusMessage = null) }

            val text = withContext(Dispatchers.IO) {
                parser.getPlainTextForChapter(state.currentChapter)
            }
            if (text.isBlank()) return@launch
            val chapterForPlayback = state.currentChapter
            val startOffset = resolveTtsStartOffset(chapterForPlayback, text)
            selectedPlaybackStartPending = false
            val textToSpeak = text.substring(startOffset)
            if (textToSpeak.isBlank()) {
                resetTtsResume()
                return@launch
            }

            val ctx = ReaderToMeApp.instance
            val intent = Intent(ctx, TtsPlaybackService::class.java).apply {
                action = TtsPlaybackService.ACTION_START
                putExtra(TtsPlaybackService.EXTRA_BOOK_TITLE, book.title)
            }
            ctx.startForegroundService(intent)

            // Read TTS display settings
            val shouldHighlight = settings.highlightDuringTts.first()
            val shouldAutoScroll = settings.autoScrollDuringTts.first()

            ttsEngine.speak(
                text = textToSpeak,
                onSentenceStart = { _, start, end ->
                    val absoluteStart = (startOffset + start).coerceIn(0, text.length)
                    val absoluteEnd = (startOffset + end).coerceIn(absoluteStart, text.length)
                    rememberTtsPosition(chapterForPlayback, absoluteStart, text.length)
                    if (shouldHighlight) {
                        evaluateJavascript?.invoke("highlightSentence($absoluteStart, $absoluteEnd, $shouldAutoScroll);")
                    } else if (shouldAutoScroll) {
                        evaluateJavascript?.invoke("scrollToTextOffset($absoluteStart);")
                    }
                },
                onComplete = {
                    if (chapterForPlayback < state.totalChapters - 1) {
                        resetTtsResume()
                        nextChapter()
                        playTts()
                    } else {
                        resetTtsResume()
                        stopTts()
                    }
                }
            )
        }
    }

    private suspend fun playHumanReaderTts(state: ReaderUiState, book: Book) {
        settings.migrateTtsDefaultsIfNeeded()
        ttsEngine.speed = settings.ttsSpeed.first()
        val selectedVoiceId = settings.selectedHumanVoiceId.first()
        val voice = AvailableVoices.voices.find { it.id == selectedVoiceId }
            ?: AvailableVoices.voices.first { it.id == AvailableVoices.HUMAN_READER_VOICE_ID }

        val humanVoiceReady = withContext(Dispatchers.IO) {
            val downloaded = ttsEngine.getDownloadedVoices().any { it.id == voice.id && it.isDownloaded }
            if (downloaded) {
                ttsEngine.initializeVoice(voice.id)
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(ttsStatusMessage = "Downloading Human Reader model. This is a one-time download.")
                    }
                }
                ttsEngine.downloadAndInitializeVoice(voice)
            }
        }

        if (!humanVoiceReady) {
            val downloaded = withContext(Dispatchers.IO) {
                ttsEngine.getDownloadedVoices().any { it.id == voice.id && it.isDownloaded }
            }
            val message = if (downloaded) {
                "Human Reader model is downloaded but could not start. Try restarting the app."
            } else {
                "Could not download Human Reader model. Check connection and free storage."
            }
            _uiState.update { it.copy(ttsStatusMessage = message) }
            return
        }

        val text = withContext(Dispatchers.IO) {
            parser.getPlainTextForChapter(state.currentChapter)
        }
        if (text.isBlank()) return

        val chapterForPlayback = state.currentChapter
        val startOffset = resolveTtsStartOffset(chapterForPlayback, text)
        val textToRead = text.substring(startOffset)
        if (textToRead.isBlank()) {
            resetTtsResume()
            return
        }

        val files = humanReaderFiles(book.id, voice.id, chapterForPlayback, startOffset)
        if (!files.wav.exists() || !files.marks.exists()) {
            setHumanReaderStatus(chapterForPlayback, "Preparing", 0f)
            _uiState.update { it.copy(ttsStatusMessage = "Human Reader is preparing this chapter.") }
            val rendered = ttsEngine.renderCurrentVoiceToWav(
                text = textToRead,
                wavFile = files.wav,
                marksFile = files.marks
            ) { progress ->
                setHumanReaderStatus(chapterForPlayback, "Preparing", progress)
                _uiState.update {
                    it.copy(ttsStatusMessage = "Human Reader preparing chapter ${(progress * 100).toInt()}%")
                }
            }

            if (!rendered) {
                setHumanReaderStatus(chapterForPlayback, "Failed", null)
                _uiState.update { it.copy(ttsStatusMessage = "Human Reader could not prepare this chapter.") }
                return
            }
        }

        setHumanReaderStatus(chapterForPlayback, "Ready", null)
        _uiState.update { it.copy(error = null, ttsStatusMessage = null) }

        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_START
            putExtra(TtsPlaybackService.EXTRA_BOOK_TITLE, book.title)
        }
        ctx.startForegroundService(intent)

        val shouldHighlight = settings.highlightDuringTts.first()
        val shouldAutoScroll = settings.autoScrollDuringTts.first()
        val marks = ttsEngine.readRenderedMarks(files.marks)

        selectedPlaybackStartPending = false
        ttsEngine.playRenderedChapter(
            wavFile = files.wav,
            marks = marks,
            onSentenceStart = { _, start, end ->
                val absoluteStart = (startOffset + start).coerceIn(0, text.length)
                val absoluteEnd = (startOffset + end).coerceIn(absoluteStart, text.length)
                rememberTtsPosition(chapterForPlayback, absoluteStart, text.length)
                if (shouldHighlight) {
                    evaluateJavascript?.invoke("highlightSentence($absoluteStart, $absoluteEnd, $shouldAutoScroll);")
                } else if (shouldAutoScroll) {
                    evaluateJavascript?.invoke("scrollToTextOffset($absoluteStart);")
                }
            },
            onComplete = {
                if (chapterForPlayback < state.totalChapters - 1) {
                    resetTtsResume()
                    nextChapter()
                    playTts()
                } else {
                    resetTtsResume()
                    stopTts()
                }
            }
        )
    }

    private data class HumanReaderFiles(val wav: File, val marks: File)

    private fun humanReaderFiles(
        bookId: Long,
        voiceId: String,
        chapterIndex: Int,
        startOffset: Int
    ): HumanReaderFiles {
        val safeVoiceId = voiceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val dir = File(ReaderToMeApp.instance.filesDir, "human_reader/book_$bookId/$safeVoiceId")
        val suffix = if (startOffset <= 0) "full" else "from_$startOffset"
        return HumanReaderFiles(
            wav = File(dir, "chapter_${chapterIndex}_$suffix.wav"),
            marks = File(dir, "chapter_${chapterIndex}_$suffix.marks")
        )
    }

    private fun refreshHumanReaderStatuses(bookId: Long, totalChapters: Int) {
        val voiceId = AvailableVoices.HUMAN_READER_VOICE_ID
        val statuses = (0 until totalChapters).mapNotNull { chapterIndex ->
            val files = humanReaderFiles(bookId, voiceId, chapterIndex, 0)
            if (files.wav.exists() && files.marks.exists()) {
                chapterIndex to HumanReaderChapterStatus("Ready")
            } else {
                null
            }
        }.toMap()
        _uiState.update { it.copy(humanReaderStatuses = statuses) }
    }

    private fun setHumanReaderStatus(chapterIndex: Int, label: String, progress: Float?) {
        _uiState.update {
            it.copy(
                humanReaderStatuses = it.humanReaderStatuses + (
                    chapterIndex to HumanReaderChapterStatus(label, progress)
                )
            )
        }
    }

    private fun resolveTtsStartOffset(chapter: Int, text: String): Int {
        if (text.isBlank()) return 0
        val rawOffset = if (ttsResumeChapter == chapter && ttsResumeOffset > 0) {
            ttsResumeOffset
        } else {
            (currentScrollProgress.coerceIn(0f, 1f) * text.length).toInt()
        }
        val boundedOffset = rawOffset.coerceIn(0, text.length)
        if (boundedOffset >= text.lastIndex) return text.length
        return clampToWordBoundary(text, boundedOffset)
    }

    private fun clampToWordBoundary(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        if (offset >= text.lastIndex) return 0
        var cursor = offset
        while (cursor > 0 && !text[cursor - 1].isWhitespace()) {
            cursor--
        }
        return cursor
    }

    private fun rememberTtsPosition(chapter: Int, absoluteStart: Int, textLength: Int) {
        if (textLength <= 0) return
        if (selectedPlaybackStartPending) return
        ttsResumeChapter = chapter
        ttsResumeOffset = absoluteStart.coerceIn(0, textLength - 1)
        val progress = (ttsResumeOffset.toFloat() / textLength).coerceIn(0f, 1f)
        currentScrollProgress = progress
        saveProgress(chapter, progress)
    }

    private fun resetTtsResume() {
        ttsResumeChapter = -1
        ttsResumeOffset = 0
        selectedPlaybackStartPending = false
    }

    private suspend fun ensureVoiceReady(): Boolean {
        val selectedVoiceId = settings.selectedVoiceId.first()
        val initialized = withContext(Dispatchers.IO) {
            ttsEngine.initializeVoice(selectedVoiceId)
        }
        if (initialized) return true

        val defaultVoice = AvailableVoices.voices.find { it.id == AvailableVoices.DEFAULT_VOICE_ID }
            ?: AvailableVoices.voices.first()
        val compactFallbackVoice = AvailableVoices.voices.find { it.id == AvailableVoices.COMPACT_FALLBACK_VOICE_ID }
        if (selectedVoiceId != defaultVoice.id) {
            val defaultInitialized = withContext(Dispatchers.IO) {
                ttsEngine.initializeVoice(defaultVoice.id)
            }
            if (defaultInitialized) {
                settings.setSelectedVoice(defaultVoice.id)
                return true
            }
        }

        val voiceToDownload = defaultVoice

        _uiState.update {
            it.copy(
                error = null,
                ttsStatusMessage = "Preparing ${voiceToDownload.name} voice..."
            )
        }

        val downloadedAndInitialized = withContext(Dispatchers.IO) {
            ttsEngine.downloadAndInitializeVoice(voiceToDownload)
        }

        if (downloadedAndInitialized) {
            settings.setSelectedVoice(voiceToDownload.id)
            _uiState.update { it.copy(ttsStatusMessage = null) }
            return true
        }

        if (compactFallbackVoice != null && compactFallbackVoice.id != voiceToDownload.id) {
            val compactInitialized = withContext(Dispatchers.IO) {
                ttsEngine.initializeVoice(compactFallbackVoice.id)
            }
            if (compactInitialized) {
                settings.setSelectedVoice(compactFallbackVoice.id)
                _uiState.update { it.copy(ttsStatusMessage = null) }
                return true
            }

            _uiState.update {
                it.copy(
                    error = null,
                    ttsStatusMessage = "Downloading compact fallback voice..."
                )
            }
            val compactDownloaded = withContext(Dispatchers.IO) {
                ttsEngine.downloadAndInitializeVoice(compactFallbackVoice)
            }
            if (compactDownloaded) {
                settings.setSelectedVoice(compactFallbackVoice.id)
                _uiState.update { it.copy(ttsStatusMessage = null) }
                return true
            }
        }

        _uiState.update {
            it.copy(
                ttsStatusMessage = null,
                error = "Could not prepare the default voice model. Please try again, or choose another voice in Settings."
            )
        }
        return false
    }

    fun pauseTts() {
        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PAUSE
        }
        ctx.startService(intent)
    }

    fun resumeTts() {
        if (selectedPlaybackStartPending && ttsResumeChapter == _uiState.value.currentChapter) {
            stopTts()
            playTts()
            return
        }

        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_RESUME
        }
        ctx.startService(intent)
    }

    fun clearTtsHighlights() {
        evaluateJavascript?.invoke("clearTtsHighlights();")
    }

    fun stopTts() {
        ttsEngine.stop()
        clearTtsHighlights()
        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    fun setFontSize(size: Float) {
        viewModelScope.launch {
            settings.setFontSize(size)
            _uiState.update { it.copy(fontSize = size) }
            reloadCurrentChapterAppearance()
        }
    }

    fun setLineSpacing(spacing: Float) {
        viewModelScope.launch {
            settings.setLineSpacing(spacing)
            _uiState.update { it.copy(lineSpacing = spacing) }
            reloadCurrentChapterAppearance()
        }
    }

    fun setReadingTheme(theme: String) {
        viewModelScope.launch {
            settings.setReadingTheme(theme)
            _uiState.update { it.copy(readingTheme = theme) }
            reloadCurrentChapterAppearance()
        }
    }

    fun setFontFamily(family: String) {
        viewModelScope.launch {
            settings.setFontFamily(family)
            _uiState.update { it.copy(fontFamily = family) }
            reloadCurrentChapterAppearance()
        }
    }

    private fun reloadCurrentChapterAppearance() {
        val state = _uiState.value
        if (state.chapterHtml.isNotBlank()) {
            loadChapterHtml(state.currentChapter)
        }
    }

    override fun onCleared() {
        super.onCleared()
        selectionResolveJob?.cancel()
        evaluateJavascript = null
        parser.close()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val MIN_READABLE_CHAPTER_CHARS = 1_000
    }
}
