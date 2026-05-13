package com.readertomeai.ui.reader

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readertomeai.ReaderToMeApp
import com.readertomeai.data.model.*
import com.readertomeai.epub.DocumentParser
import com.readertomeai.tts.HumanReaderPreparationService
import com.readertomeai.tts.TtsPlaybackService
import com.readertomeai.tts.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    val humanReaderStatuses: Map<Int, HumanReaderChapterStatus> = emptyMap(),
    val readerMode: ReaderMode = ReaderMode.INSTANT,
    val isHumanReaderPreparing: Boolean = false,
    val humanReaderRequiredChapters: List<Int> = emptyList(),
    val humanReaderReadyCount: Int = 0,
    val humanReaderPlayableChapter: Int? = null
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
    private var humanReaderPrepareJob: Job? = null

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

        viewModelScope.launch {
            settings.readerMode.collect { mode ->
                _uiState.update { it.copy(readerMode = mode) }
            }
        }

        viewModelScope.launch {
            HumanReaderPreparationService.progress.collect { prep ->
                val book = _uiState.value.book ?: return@collect
                if (prep.bookId != book.id) return@collect

                refreshHumanReaderStatuses(book.id, _uiState.value.totalChapters)
                _uiState.update { state ->
                    val activeStatuses = if (prep.isRunning && prep.currentChapter != null) {
                        state.humanReaderStatuses + (
                            prep.currentChapter to HumanReaderChapterStatus(
                                "Preparing",
                                prep.currentChapterProgress
                            )
                        )
                    } else {
                        state.humanReaderStatuses.filterValues {
                            it.label != "Queued" && it.label != "Preparing"
                        }
                    }
                    state.copy(
                        isHumanReaderPreparing = prep.isRunning,
                        ttsStatusMessage = prep.message,
                        humanReaderStatuses = activeStatuses,
                        humanReaderReadyCount = readyHumanReaderChapterCount(
                            statuses = activeStatuses,
                            requiredChapters = state.humanReaderRequiredChapters
                        ),
                        humanReaderPlayableChapter = firstPlayableHumanReaderChapterFromStatuses(
                            statuses = activeStatuses,
                            requiredChapters = state.humanReaderRequiredChapters,
                            startChapter = state.currentChapter
                        )
                    )
                }
            }
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
            val humanReaderRequiredChapters = withContext(Dispatchers.IO) {
                findHumanReaderRequiredChapters(totalChapters)
            }
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
                    savedScrollPosition = initialPosition,
                    humanReaderRequiredChapters = humanReaderRequiredChapters,
                    humanReaderPlayableChapter = firstPlayableHumanReaderChapterFromStatuses(
                        statuses = it.humanReaderStatuses,
                        requiredChapters = humanReaderRequiredChapters,
                        startChapter = initialChapter
                    )
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
                if (HumanReaderPreparationService.isPreparingAny()) return@launch
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

    private fun findHumanReaderRequiredChapters(totalChapters: Int): List<Int> =
        (0 until totalChapters).filter { parser.getPlainTextForChapter(it).isNotBlank() }

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
        _uiState.update {
            it.copy(
                savedScrollPosition = 0f,
                humanReaderPlayableChapter = firstPlayableHumanReaderChapterFromStatuses(
                    statuses = it.humanReaderStatuses,
                    requiredChapters = it.humanReaderRequiredChapters,
                    startChapter = index
                )
            )
        }
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

    fun prepareCurrentHumanReaderChapter() {
        prepareAllHumanReaderChapters()
    }

    fun prepareNextHumanReaderChapters(count: Int = 3) {
        prepareAllHumanReaderChapters()
    }

    fun prepareAllHumanReaderChapters() {
        prepareHumanReaderChapters(_uiState.value.humanReaderRequiredChapters)
    }

    fun downloadHumanReaderBook() {
        prepareAllHumanReaderChapters()
    }

    fun setReaderMode(mode: ReaderMode) {
        viewModelScope.launch {
            if (mode != _uiState.value.readerMode) {
                stopTts()
            }
            if (mode == ReaderMode.INSTANT) {
                pauseHumanReaderPreparationForInstant()
            }
            settings.setReaderMode(mode)
            _uiState.update { state ->
                val message = if (mode == ReaderMode.HUMAN && !state.isHumanReaderBookReady()) {
                    if (state.humanReaderPlayableChapter != null) {
                        "Human Reader can play prepared chapters while the rest downloads."
                    } else {
                        "Download Human Reader chapters before playback."
                    }
                } else {
                    state.ttsStatusMessage
                }
                state.copy(readerMode = mode, ttsStatusMessage = message)
            }
        }
    }

    fun cancelHumanReaderPreparation() {
        humanReaderPrepareJob?.cancel()
        humanReaderPrepareJob = null
        stopHumanReaderPreparationService()
        _uiState.update { state ->
            state.copy(
                isHumanReaderPreparing = false,
                ttsStatusMessage = "Human Reader preparation stopped.",
                humanReaderStatuses = state.humanReaderStatuses.filterValues {
                    it.label != "Queued" && it.label != "Preparing"
                }
            )
        }
    }

    private suspend fun pauseHumanReaderPreparationForInstant() {
        humanReaderPrepareJob?.cancelAndJoin()
        humanReaderPrepareJob = null
        stopHumanReaderPreparationService()
        _uiState.update { state ->
            state.copy(
                isHumanReaderPreparing = false,
                ttsStatusMessage = null,
                humanReaderStatuses = state.humanReaderStatuses.filterValues {
                    it.label != "Queued" && it.label != "Preparing"
                }
            )
        }
    }

    private fun prepareHumanReaderChapters(chapterIndices: List<Int>) {
        val state = _uiState.value
        val book = state.book ?: return
        if (state.humanReaderRequiredChapters.isEmpty()) {
            _uiState.update { it.copy(ttsStatusMessage = "This book has no readable chapters for Human Reader.") }
            return
        }
        val boundedChapters = chapterIndices
            .filter { it in state.humanReaderRequiredChapters }
            .distinct()

        if (boundedChapters.isEmpty()) return

        stopTts()
        humanReaderPrepareJob?.cancel()
        humanReaderPrepareJob = viewModelScope.launch {
            settings.migrateTtsDefaultsIfNeeded()
            val voice = selectedHumanVoice()
            val pendingChapters = boundedChapters.filterNot {
                humanReaderChapterReady(book.id, voice.id, it, 0)
            }
            if (pendingChapters.isEmpty()) {
                refreshHumanReaderStatuses(book.id, state.totalChapters)
                _uiState.update {
                    it.copy(
                        isHumanReaderPreparing = false,
                        ttsStatusMessage = "Human Reader is ready."
                    )
                }
                humanReaderPrepareJob = null
                return@launch
            }

            pendingChapters.forEach { setHumanReaderStatus(it, "Queued", null) }
            _uiState.update {
                it.copy(
                    isHumanReaderPreparing = true,
                    ttsStatusMessage = "Preparing full Human Reader book: ${it.humanReaderReadyCount}/${it.humanReaderRequiredChapters.size} chapters ready. This can run overnight."
                )
            }
            startHumanReaderPreparationService(book, voice.id)
            humanReaderPrepareJob = null
        }
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

            pauseHumanReaderPreparationForInstant()
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
        val currentState = _uiState.value
        if (currentState.humanReaderRequiredChapters.isEmpty()) {
            _uiState.update { it.copy(ttsStatusMessage = "This book has no readable chapters for Human Reader.") }
            return
        }

        settings.migrateTtsDefaultsIfNeeded()
        ttsEngine.speed = settings.ttsSpeed.first()
        val voice = selectedHumanVoice()

        if (!ensureHumanReaderVoiceReady(voice)) return

        val chapterForPlayback = firstPlayableHumanReaderChapter(
            bookId = book.id,
            voiceId = voice.id,
            state = currentState,
            startChapter = state.currentChapter
        )
        if (chapterForPlayback == null) {
            refreshHumanReaderStatuses(book.id, state.totalChapters)
            _uiState.update {
                it.copy(
                    ttsStatusMessage = if (it.isHumanReaderPreparing) {
                        "Human Reader is still preparing the next playable chapter."
                    } else {
                        "Download Human Reader chapters before playback."
                    }
                )
            }
            return
        }

        if (chapterForPlayback != state.currentChapter) {
            currentScrollProgress = 0f
            resetTtsResume()
            _uiState.update { it.copy(savedScrollPosition = 0f) }
            loadChapterHtml(chapterForPlayback)
            saveProgress(chapterForPlayback, 0f)
        }

        val text = withContext(Dispatchers.IO) {
            parser.getPlainTextForChapter(chapterForPlayback)
        }
        if (text.isBlank()) return

        val resolvedStartOffset = if (chapterForPlayback == state.currentChapter) {
            resolveTtsStartOffset(chapterForPlayback, text)
        } else {
            0
        }
        val startOffset = if (resolvedStartOffset >= text.length - 50) 0 else resolvedStartOffset
        val files = humanReaderFiles(book.id, voice.id, chapterForPlayback, 0)
        if (!isHumanReaderCacheReady(files)) {
            refreshHumanReaderStatuses(book.id, state.totalChapters)
            _uiState.update { it.copy(ttsStatusMessage = "Human Reader is not ready yet.") }
            return
        }

        setHumanReaderStatus(chapterForPlayback, "Ready", null)
        _uiState.update {
            it.copy(
                error = null,
                ttsStatusMessage = if (chapterForPlayback != state.currentChapter) {
                    "Playing the first prepared Human Reader chapter."
                } else {
                    null
                }
            )
        }

        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_START
            putExtra(TtsPlaybackService.EXTRA_BOOK_TITLE, book.title)
        }
        ctx.startForegroundService(intent)

        val shouldHighlight = settings.highlightDuringTts.first()
        val shouldAutoScroll = settings.autoScrollDuringTts.first()
        val marks = ttsEngine.readRenderedMarks(files.marks)
        val startMs = marks.firstOrNull { it.endChar >= startOffset }?.startMs ?: 0L

        selectedPlaybackStartPending = false
        ttsEngine.playRenderedChapter(
            wavFile = files.wav,
            marks = marks,
            startMs = startMs,
            onSentenceStart = { _, start, end ->
                val absoluteStart = start.coerceIn(0, text.length)
                val absoluteEnd = end.coerceIn(absoluteStart, text.length)
                rememberTtsPosition(chapterForPlayback, absoluteStart, text.length)
                if (shouldHighlight) {
                    evaluateJavascript?.invoke("highlightSentence($absoluteStart, $absoluteEnd, $shouldAutoScroll);")
                } else if (shouldAutoScroll) {
                    evaluateJavascript?.invoke("scrollToTextOffset($absoluteStart);")
                }
            },
            onComplete = {
                val latestState = _uiState.value
                val nextReadyChapter = firstPlayableHumanReaderChapter(
                    bookId = book.id,
                    voiceId = voice.id,
                    state = latestState,
                    startChapter = chapterForPlayback + 1
                )
                if (nextReadyChapter != null) {
                    resetTtsResume()
                    goToChapter(nextReadyChapter)
                    playTts()
                } else {
                    resetTtsResume()
                    stopTts()
                    _uiState.update {
                        it.copy(
                            ttsStatusMessage = if (it.isHumanReaderPreparing) {
                                "Reached the end of prepared Human Reader audio. Download is still preparing more."
                            } else if (it.isHumanReaderBookReady()) {
                                "Human Reader finished."
                            } else {
                                "Reached the end of prepared Human Reader audio. Resume the download for more chapters."
                            }
                        )
                    }
                }
            }
        )
    }

    private suspend fun selectedHumanVoice(): VoiceModel {
        val selectedVoiceId = settings.selectedHumanVoiceId.first()
        return AvailableVoices.voices.find { it.id == selectedVoiceId }
            ?: AvailableVoices.voices.first { it.id == AvailableVoices.HUMAN_READER_VOICE_ID }
    }

    private suspend fun ensureHumanReaderVoiceReady(voice: VoiceModel): Boolean {
        if (ttsEngine.currentVoice.value?.id == voice.id) return true

        val downloaded = withContext(Dispatchers.IO) {
            ttsEngine.getDownloadedVoices().any { it.id == voice.id && it.isDownloaded }
        }

        val ready = if (downloaded) {
            withContext(Dispatchers.IO) { ttsEngine.initializeVoice(voice.id) }
        } else {
            _uiState.update {
                it.copy(ttsStatusMessage = "Downloading Human Reader model. This is a one-time download.")
            }
            withContext(Dispatchers.IO) { ttsEngine.downloadAndInitializeVoice(voice) }
        }

        if (!ready) {
            val nowDownloaded = withContext(Dispatchers.IO) {
                ttsEngine.getDownloadedVoices().any { it.id == voice.id && it.isDownloaded }
            }
            val message = if (nowDownloaded) {
                "Human Reader model is downloaded but could not start. Try restarting the app."
            } else {
                "Could not download Human Reader model. Check connection and free storage."
            }
            _uiState.update { it.copy(ttsStatusMessage = message) }
        }

        return ready
    }

    private suspend fun prepareHumanReaderChapter(
        book: Book,
        voice: VoiceModel,
        chapterIndex: Int,
        startOffset: Int,
        textOverride: String?,
        progressMessage: ((Float) -> String)? = null
    ): Boolean {
        val files = humanReaderFiles(book.id, voice.id, chapterIndex, startOffset)
        if (isHumanReaderCacheReady(files)) return true
        files.tempWav.delete()
        files.tempMarks.delete()

        val text = textOverride ?: withContext(Dispatchers.IO) {
            parser.getPlainTextForChapter(chapterIndex)
        }
        val boundedStart = startOffset.coerceIn(0, text.length)
        val textToRead = text.substring(boundedStart)
        if (textToRead.isBlank()) {
            setHumanReaderStatus(chapterIndex, "Failed", null)
            return false
        }

        val estimatedBytes = estimateHumanReaderAudioBytes(textToRead)
        if (!hasEnoughHumanReaderStorage(estimatedBytes)) {
            setHumanReaderStatus(chapterIndex, "Failed", null)
            _uiState.update { it.copy(ttsStatusMessage = humanReaderStorageMessage(estimatedBytes)) }
            return false
        }

        setHumanReaderStatus(chapterIndex, "Preparing", 0f)
        val rendered = ttsEngine.renderCurrentVoiceToWav(
            text = textToRead,
            wavFile = files.tempWav,
            marksFile = files.tempMarks
        ) { progress ->
            setHumanReaderStatus(chapterIndex, "Preparing", progress)
            progressMessage?.invoke(progress)?.let { message ->
                _uiState.update { it.copy(ttsStatusMessage = message) }
            }
        }

        val committed = rendered && commitHumanReaderCache(files)
        setHumanReaderStatus(
            chapterIndex = chapterIndex,
            label = if (committed) {
                if (startOffset <= 0) "Ready" else "Ready from here"
            } else {
                "Failed"
            },
            progress = null
        )
        if (!committed) {
            files.tempWav.delete()
            files.tempMarks.delete()
        }
        return committed
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
        File(ReaderToMeApp.instance.filesDir, "human_reader/book_$bookId/$safeVoiceId")

    private fun humanReaderChapterReady(
        bookId: Long,
        voiceId: String,
        chapterIndex: Int,
        startOffset: Int
    ): Boolean {
        val files = humanReaderFiles(bookId, voiceId, chapterIndex, startOffset)
        return isHumanReaderCacheReady(files)
    }

    private fun refreshHumanReaderStatuses(bookId: Long, totalChapters: Int) {
        val voiceId = AvailableVoices.HUMAN_READER_VOICE_ID
        val statuses = (0 until totalChapters).mapNotNull { chapterIndex ->
            val files = humanReaderFiles(bookId, voiceId, chapterIndex, 0)
            if (isHumanReaderCacheReady(files)) {
                chapterIndex to HumanReaderChapterStatus("Ready")
            } else {
                null
            }
        }.toMap()
        _uiState.update { state ->
            val activeStatuses = state.humanReaderStatuses.filterValues {
                it.label != "Ready" && it.label != "Ready from here"
            }
            val mergedStatuses = activeStatuses + statuses
            state.copy(
                humanReaderStatuses = mergedStatuses,
                humanReaderReadyCount = readyHumanReaderChapterCount(
                    statuses = mergedStatuses,
                    requiredChapters = state.humanReaderRequiredChapters
                ),
                humanReaderPlayableChapter = firstPlayableHumanReaderChapterFromStatuses(
                    statuses = mergedStatuses,
                    requiredChapters = state.humanReaderRequiredChapters,
                    startChapter = state.currentChapter
                )
            )
        }
    }

    private fun setHumanReaderStatus(chapterIndex: Int, label: String, progress: Float?) {
        _uiState.update {
            val statuses = it.humanReaderStatuses + (
                chapterIndex to HumanReaderChapterStatus(label, progress)
            )
            it.copy(
                humanReaderStatuses = statuses,
                humanReaderReadyCount = readyHumanReaderChapterCount(
                    statuses = statuses,
                    requiredChapters = it.humanReaderRequiredChapters
                ),
                humanReaderPlayableChapter = firstPlayableHumanReaderChapterFromStatuses(
                    statuses = statuses,
                    requiredChapters = it.humanReaderRequiredChapters,
                    startChapter = it.currentChapter
                )
            )
        }
    }

    private fun readyHumanReaderChapterCount(
        statuses: Map<Int, HumanReaderChapterStatus>,
        requiredChapters: List<Int>
    ): Int = requiredChapters.count { statuses[it]?.label == "Ready" }

    private fun ReaderUiState.isHumanReaderBookReady(): Boolean =
        humanReaderRequiredChapters.isNotEmpty() &&
            humanReaderReadyCount >= humanReaderRequiredChapters.size

    private fun firstPlayableHumanReaderChapter(
        bookId: Long,
        voiceId: String,
        state: ReaderUiState,
        startChapter: Int
    ): Int? {
        val readyChapters = state.humanReaderRequiredChapters.filter { chapterIndex ->
            humanReaderChapterReady(bookId, voiceId, chapterIndex, 0)
        }
        return readyChapters.firstOrNull { it >= startChapter } ?: readyChapters.firstOrNull()
    }

    private fun firstPlayableHumanReaderChapterFromStatuses(
        statuses: Map<Int, HumanReaderChapterStatus>,
        requiredChapters: List<Int>,
        startChapter: Int
    ): Int? {
        val readyChapters = requiredChapters.filter { chapterIndex ->
            statuses[chapterIndex]?.label == "Ready"
        }
        return readyChapters.firstOrNull { it >= startChapter } ?: readyChapters.firstOrNull()
    }

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
        return ttsEngine.isPlayableRenderedWav(file)
    }

    private fun isValidMarksFile(file: File): Boolean =
        file.exists() && file.length() > 0L &&
            runCatching { ttsEngine.readRenderedMarks(file).isNotEmpty() }.getOrDefault(false)

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
        bookId: Long,
        voiceId: String,
        chapters: List<Int>
    ): Long = chapters.sumOf { chapterIndex ->
        if (humanReaderChapterReady(bookId, voiceId, chapterIndex, 0)) {
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
        return ReaderToMeApp.instance.filesDir.usableSpace > neededBytes
    }

    private fun humanReaderStorageMessage(estimatedBytes: Long): String {
        val neededMb = bytesToMb(estimatedBytes + MIN_HUMAN_READER_CACHE_FREE_BYTES)
        val freeMb = bytesToMb(ReaderToMeApp.instance.filesDir.usableSpace)
        return "Human Reader needs about ${neededMb} MB free to finish this book. Free space now: ${freeMb} MB."
    }

    private fun bytesToMb(bytes: Long): Long =
        (bytes / (1024L * 1024L)).coerceAtLeast(1L)

    private fun startHumanReaderPreparationService(book: Book, voiceId: String) {
        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, HumanReaderPreparationService::class.java).apply {
            action = HumanReaderPreparationService.ACTION_START
            putExtra(HumanReaderPreparationService.EXTRA_BOOK_ID, book.id)
            putExtra(HumanReaderPreparationService.EXTRA_BOOK_TITLE, book.title)
            putExtra(HumanReaderPreparationService.EXTRA_VOICE_ID, voiceId)
        }
        ctx.startForegroundService(intent)
    }

    private fun stopHumanReaderPreparationService() {
        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, HumanReaderPreparationService::class.java).apply {
            action = HumanReaderPreparationService.ACTION_STOP
        }
        ctx.startService(intent)
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
        humanReaderPrepareJob?.cancel()
        evaluateJavascript = null
        parser.close()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val MIN_READABLE_CHAPTER_CHARS = 1_000
        const val MIN_HUMAN_READER_WAV_BYTES = 44L
        const val MIN_HUMAN_READER_CACHE_FREE_BYTES = 300L * 1024L * 1024L
        const val HUMAN_READER_ESTIMATED_BYTES_PER_WORD = 24_000L
    }
}
