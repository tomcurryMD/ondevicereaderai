package com.readertomeai.ui.reader

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readertomeai.tts.TtsState
import com.readertomeai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()
    val ttsProgress by viewModel.ttsProgress.collectAsState()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Purple)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Opening book…")
            }
        }
        return
    }

    if (uiState.error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Error, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Text(uiState.error!!)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Go Back") }
            }
        }
        return
    }

    val readingBg = when (uiState.readingTheme) {
        "dark" -> DarkReadingColors.background
        "sepia" -> SepiaReadingColors.background
        else -> LightReadingColors.background
    }

    Box(modifier = Modifier.fillMaxSize().background(readingBg)) {
        // WebView Reader
        ReaderWebView(
            html = uiState.chapterHtml,
            initialPosition = uiState.savedScrollPosition,
            onTap = { viewModel.toggleControls() },
            onScrollProgress = { viewModel.onScrollProgress(it) },
            onTextSelected = { text, startOffset, endOffset ->
                viewModel.onTextSelected(text, startOffset, endOffset)
            },
            onWebViewReady = { jsEvaluator ->
                // Wire up so ViewModel can call highlightSentence() / clearTtsHighlights()
                viewModel.evaluateJavascript = jsEvaluator
            }
        )

        // Top bar
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            uiState.book?.title ?: "",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            uiState.chapterTitle,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showToc() }) {
                        Icon(Icons.Outlined.Toc, "Table of Contents")
                    }
                    IconButton(onClick = { viewModel.showBookmarks() }) {
                        Icon(Icons.Outlined.Bookmarks, "Bookmarks")
                    }
                    IconButton(onClick = { viewModel.addBookmark() }) {
                        Icon(Icons.Outlined.BookmarkAdd, "Add Bookmark")
                    }
                    IconButton(onClick = { viewModel.showSettings() }) {
                        Icon(Icons.Outlined.TextFormat, "Reading Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = readingBg.copy(alpha = 0.95f)
                )
            )
        }

        // Bottom TTS controls
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            TtsControlBar(
                ttsState = ttsState,
                currentChapter = uiState.currentChapter,
                totalChapters = uiState.totalChapters,
                chapterTitle = uiState.chapterTitle,
                onPlay = { viewModel.playTts() },
                onPause = { viewModel.pauseTts() },
                onResume = { viewModel.resumeTts() },
                onStop = { viewModel.stopTts() },
                onPreviousChapter = { viewModel.previousChapter() },
                onNextChapter = { viewModel.nextChapter() },
                backgroundColor = readingBg
            )
        }

        // Table of Contents drawer
        if (uiState.showToc) {
            TocDrawer(
                toc = uiState.tableOfContents,
                currentChapter = uiState.currentChapter,
                onChapterSelect = {
                    viewModel.goToChapter(it)
                    viewModel.hideToc()
                },
                onDismiss = { viewModel.hideToc() }
            )
        }

        // Bookmarks drawer
        if (uiState.showBookmarks) {
            BookmarksDrawer(
                bookmarks = uiState.bookmarks,
                onBookmarkClick = { bookmark ->
                    viewModel.goToChapter(bookmark.chapterIndex)
                    viewModel.hideBookmarks()
                },
                onBookmarkDelete = { viewModel.removeBookmark(it) },
                onDismiss = { viewModel.hideBookmarks() }
            )
        }

        // Reading settings bottom sheet
        if (uiState.showSettings) {
            ReadingSettingsSheet(
                fontSize = uiState.fontSize,
                lineSpacing = uiState.lineSpacing,
                readingTheme = uiState.readingTheme,
                fontFamily = uiState.fontFamily,
                onFontSizeChange = { viewModel.setFontSize(it) },
                onLineSpacingChange = { viewModel.setLineSpacing(it) },
                onThemeChange = { viewModel.setReadingTheme(it) },
                onFontFamilyChange = { viewModel.setFontFamily(it) },
                onDismiss = { viewModel.hideSettings() }
            )
        }
    }
}

/**
 * WebView-based reader.
 *
 * Fixes:
 * - Tracks the last-loaded HTML hash to avoid reloading on unrelated recompositions
 * - Sanitizes book HTML: strips <script> tags from untrusted content
 * - JS bridge methods validate inputs
 * - Blocks all external navigation
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderWebView(
    html: String,
    initialPosition: Float,
    onTap: () -> Unit,
    onScrollProgress: (Float) -> Unit,
    onTextSelected: (String, Int, Int) -> Unit,
    onWebViewReady: ((jsEvaluator: (String) -> Unit) -> Unit)? = null
) {
    val loadedHtmlHash = remember { mutableIntStateOf(0) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // Expose JS evaluator to caller (for TTS highlighting)
                onWebViewReady?.invoke { js ->
                    post { evaluateJavascript(js, null) }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = false
                settings.setSupportZoom(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean = true // Block ALL navigation

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (initialPosition > 0f) {
                            view?.evaluateJavascript("scrollToProgress($initialPosition);", null)
                        }
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onScrollProgress(progress: Float) {
                        onScrollProgress(progress.coerceIn(0f, 1f))
                    }

                    @JavascriptInterface
                    fun onTextSelected(text: String) {
                        val safe = text.take(5000)
                        onTextSelected(safe, 0, safe.length)
                    }
                }, "Android")
            }
        },
        update = { webView ->
            val newHash = html.hashCode()
            if (newHash != loadedHtmlHash.intValue) {
                loadedHtmlHash.intValue = newHash
                webView.loadDataWithBaseURL(
                    "file:///android_asset/", html, "text/html", "UTF-8", null
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun TtsControlBar(
    ttsState: TtsState,
    currentChapter: Int,
    totalChapters: Int,
    chapterTitle: String,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    backgroundColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = backgroundColor.copy(alpha = 0.95f),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Ch. ${currentChapter + 1} of $totalChapters",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { if (totalChapters > 0) (currentChapter + 1f) / totalChapters else 0f },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Purple,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousChapter, enabled = currentChapter > 0) {
                    Icon(Icons.Filled.SkipPrevious, "Previous Chapter", modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))

                when (ttsState) {
                    TtsState.IDLE, TtsState.LOADING -> {
                        FloatingActionButton(
                            onClick = onPlay,
                            containerColor = Purple,
                            contentColor = Color.White,
                            modifier = Modifier.size(56.dp)
                        ) {
                            if (ttsState == TtsState.LOADING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Filled.PlayArrow, "Play", modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    TtsState.SPEAKING -> {
                        FloatingActionButton(
                            onClick = onPause, containerColor = Purple, contentColor = Color.White,
                            modifier = Modifier.size(56.dp)
                        ) { Icon(Icons.Filled.Pause, "Pause", modifier = Modifier.size(32.dp)) }
                    }
                    TtsState.PAUSED -> {
                        FloatingActionButton(
                            onClick = onResume, containerColor = Purple, contentColor = Color.White,
                            modifier = Modifier.size(56.dp)
                        ) { Icon(Icons.Filled.PlayArrow, "Resume", modifier = Modifier.size(32.dp)) }
                    }
                }

                if (ttsState == TtsState.SPEAKING || ttsState == TtsState.PAUSED) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, "Stop", modifier = Modifier.size(28.dp))
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))
                IconButton(onClick = onNextChapter, enabled = currentChapter < totalChapters - 1) {
                    Icon(Icons.Filled.SkipNext, "Next Chapter", modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
fun TocDrawer(
    toc: List<com.readertomeai.data.model.TocEntry>,
    currentChapter: Int,
    onChapterSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Table of Contents", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                items(toc) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterSelect(entry.chapterIndex) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (entry.chapterIndex == currentChapter) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Purple))
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            entry.title,
                            fontWeight = if (entry.chapterIndex == currentChapter) FontWeight.Bold else FontWeight.Normal,
                            color = if (entry.chapterIndex == currentChapter) Purple else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = if (entry.chapterIndex != currentChapter) 20.dp else 0.dp)
                        )
                    }
                    if (entry != toc.last()) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun BookmarksDrawer(
    bookmarks: List<com.readertomeai.data.model.Bookmark>,
    onBookmarkClick: (com.readertomeai.data.model.Bookmark) -> Unit,
    onBookmarkDelete: (com.readertomeai.data.model.Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks", fontWeight = FontWeight.Bold) },
        text = {
            if (bookmarks.isEmpty()) {
                Text("No bookmarks yet. Tap the bookmark icon to add one.")
            } else {
                LazyColumn {
                    items(bookmarks) { bookmark ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onBookmarkClick(bookmark) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Bookmark, null, tint = Purple, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bookmark.label, fontWeight = FontWeight.Medium)
                                Text("Chapter ${bookmark.chapterIndex + 1} · ${(bookmark.position * 100).toInt()}%",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onBookmarkDelete(bookmark) }) {
                                Icon(Icons.Outlined.Delete, "Delete", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsSheet(
    fontSize: Float,
    lineSpacing: Float,
    readingTheme: String,
    fontFamily: String,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onThemeChange: (String) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Reading Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(24.dp))

            Text("Font Size: ${fontSize.toInt()}px", fontWeight = FontWeight.Medium)
            Slider(
                value = fontSize, onValueChange = onFontSizeChange,
                valueRange = 12f..32f, steps = 19,
                colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("Line Spacing: ${"%.1f".format(lineSpacing)}", fontWeight = FontWeight.Medium)
            Slider(
                value = lineSpacing, onValueChange = onLineSpacingChange,
                valueRange = 1.0f..2.5f, steps = 14,
                colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("Theme", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChip("Light", "light", readingTheme == "light", onThemeChange)
                ThemeChip("Dark", "dark", readingTheme == "dark", onThemeChange)
                ThemeChip("Sepia", "sepia", readingTheme == "sepia", onThemeChange)
                ThemeChip("Auto", "system", readingTheme == "system", onThemeChange)
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("Font", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FontChip("Sans", "default", fontFamily == "default", onFontFamilyChange)
                FontChip("Serif", "serif", fontFamily == "serif", onFontFamilyChange)
                FontChip("Mono", "mono", fontFamily == "mono", onFontFamilyChange)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ThemeChip(label: String, value: String, selected: Boolean, onSelect: (String) -> Unit) {
    FilterChip(selected = selected, onClick = { onSelect(value) }, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Purple, selectedLabelColor = Color.White))
}

@Composable
fun FontChip(label: String, value: String, selected: Boolean, onSelect: (String) -> Unit) {
    FilterChip(selected = selected, onClick = { onSelect(value) }, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Purple, selectedLabelColor = Color.White))
}
