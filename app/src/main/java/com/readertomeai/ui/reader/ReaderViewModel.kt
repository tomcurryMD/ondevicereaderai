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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val selectedTextEnd: Int = 0
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

            _uiState.update {
                it.copy(
                    book = book,
                    totalChapters = parser.getChapterCount(),
                    tableOfContents = toc,
                    currentChapter = book.currentChapter,
                    savedScrollPosition = book.currentPosition
                )
            }

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
                settings.ttsSpeed.first().let { speed ->
                    ttsEngine.speed = speed
                }
                settings.selectedVoiceId.first().let { voiceId ->
                    ttsEngine.initializeVoice(voiceId)
                }
            }

            loadChapterHtml(book.currentChapter)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

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
        _uiState.update { it.copy(savedScrollPosition = 0f) }
        loadChapterHtml(index)
        saveProgress(index, 0f)
    }

    fun nextChapter() = goToChapter(_uiState.value.currentChapter + 1)
    fun previousChapter() = goToChapter(_uiState.value.currentChapter - 1)

    /** Navigate to a bookmark, restoring both chapter and scroll position */
    fun goToBookmark(bookmark: com.readertomeai.data.model.Bookmark) {
        if (bookmark.chapterIndex < 0 || bookmark.chapterIndex >= _uiState.value.totalChapters) return
        ttsEngine.stop()
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
        _uiState.update {
            it.copy(selectedText = text, selectedTextStart = startOffset, selectedTextEnd = endOffset)
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

    // ── TTS Controls ────────────────────────────────────────────────

    fun playTts() {
        val state = _uiState.value
        val book = state.book ?: return

        viewModelScope.launch {
            ttsEngine.speed = settings.ttsSpeed.first()
            if (ttsEngine.currentVoice.value == null) {
                val selectedVoiceId = settings.selectedVoiceId.first()
                val initialized = withContext(Dispatchers.IO) {
                    ttsEngine.initializeVoice(selectedVoiceId)
                }
                if (!initialized) {
                    _uiState.update {
                        it.copy(error = "Download and select a voice model before using TTS.")
                    }
                    return@launch
                }
            }
            _uiState.update { it.copy(error = null) }

            val text = withContext(Dispatchers.IO) {
                parser.getPlainTextForChapter(state.currentChapter)
            }
            if (text.isBlank()) return@launch

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
                text = text,
                onSentenceStart = { _, start, end ->
                    if (shouldHighlight) {
                        evaluateJavascript?.invoke("highlightSentence($start, $end);")
                    } else if (shouldAutoScroll) {
                        // Auto-scroll without highlighting � just scroll to the sentence
                        evaluateJavascript?.invoke(
                            "var el=document.body;var total=el.scrollHeight-window.innerHeight;" +
                            "var pos=$start/el.textContent.length*total;" +
                            "window.scrollTo({top:pos,behavior:'smooth'});"
                        )
                    }
                },
                onComplete = {
                    if (state.currentChapter < state.totalChapters - 1) {
                        nextChapter()
                        playTts()
                    } else {
                        stopTts()
                    }
                }
            )
        }
    }

    fun pauseTts() = ttsEngine.pause()
    fun resumeTts() = ttsEngine.resume()

    fun stopTts() {
        ttsEngine.stop()
        evaluateJavascript?.invoke("clearTtsHighlights();")
        val ctx = ReaderToMeApp.instance
        val intent = Intent(ctx, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    fun setFontSize(size: Float) { viewModelScope.launch { settings.setFontSize(size) } }
    fun setLineSpacing(spacing: Float) { viewModelScope.launch { settings.setLineSpacing(spacing) } }
    fun setReadingTheme(theme: String) { viewModelScope.launch { settings.setReadingTheme(theme) } }
    fun setFontFamily(family: String) { viewModelScope.launch { settings.setFontFamily(family) } }

    override fun onCleared() {
        super.onCleared()
        evaluateJavascript = null
        parser.close()
    }
}
