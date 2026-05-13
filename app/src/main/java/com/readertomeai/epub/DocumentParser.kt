package com.readertomeai.epub

import android.content.Context
import com.readertomeai.data.model.ChapterContent
import com.readertomeai.data.model.TocEntry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.io.File

enum class DocumentType {
    EPUB, PDF, HTML, UNKNOWN
}

/**
 * Unified document parser that handles ePub, PDF, and HTML files.
 * Wraps EpubParser for ePub files and provides native parsing for PDF/HTML.
 */
class DocumentParser(private val context: Context) {

    private val epubParser = EpubParser(context)

    // PDF state
    private var pdfDocument: PDDocument? = null
    private var pdfPages: List<String> = emptyList()

    // HTML state
    private var htmlContent: String? = null
    private var htmlTitle: String = ""
    private var htmlPlainText: String = ""

    private var currentType: DocumentType = DocumentType.UNKNOWN
    private var currentFilePath: String? = null

    companion object {
        private var pdfBoxInitialized = false
        private val REMOTE_REFERENCE = Regex("""(?i)(https?:)?//.*""")
        private val REMOTE_STYLE_URL = Regex("""(?i)url\(\s*['"]?\s*(https?:)?//""")

        fun detectType(filePath: String): DocumentType {
            val lower = filePath.lowercase()
            return when {
                lower.endsWith(".epub") -> DocumentType.EPUB
                lower.endsWith(".pdf") -> DocumentType.PDF
                lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml") -> DocumentType.HTML
                else -> DocumentType.UNKNOWN
            }
        }

        fun supportedExtensions(): Array<String> = arrayOf("application/epub+zip", "application/pdf", "text/html")
        fun supportedMimeTypes(): Array<String> = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "text/html",
            "application/xhtml+xml"
        )

        /**
         * Shared JavaScript for all reader WebViews (ePub uses its own copy in EpubParser).
         * Includes scroll tracking, TTS sentence highlighting, and text selection.
         */
        private val READER_JS = """
            var lastReportedProgress = -1;

            function removeStandaloneOpenControls() {
                if (!document.body) return;
                document.querySelectorAll('a, button, [role="button"], p, div, span, li').forEach(function(el) {
                    if (el.textContent && el.textContent.trim().toLowerCase() === 'open') {
                        el.remove();
                    }
                });
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                var textNodes = [];
                var node;
                while (node = walker.nextNode()) {
                    if (node.textContent && node.textContent.trim().toLowerCase() === 'open') {
                        textNodes.push(node);
                    }
                }
                textNodes.forEach(function(textNode) {
                    if (textNode.parentNode) textNode.parentNode.removeChild(textNode);
                });
            }

            document.addEventListener('DOMContentLoaded', removeStandaloneOpenControls);
            window.addEventListener('load', removeStandaloneOpenControls);

            window.addEventListener('scroll', function() {
                var scrollTop = window.scrollY;
                var docHeight = document.documentElement.scrollHeight - window.innerHeight;
                var progress = docHeight > 0 ? scrollTop / docHeight : 0;
                var rounded = Math.round(progress * 1000) / 1000;
                if (rounded !== lastReportedProgress) {
                    lastReportedProgress = rounded;
                    if (typeof Android !== 'undefined') Android.onScrollProgress(rounded);
                }
            });

            function highlightSentence(startIdx, endIdx, shouldScroll) {
                if (shouldScroll === undefined) shouldScroll = true;
                clearTtsHighlights();
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                var currentOffset = 0;
                var node;
                while (node = walker.nextNode()) {
                    var nodeLen = node.textContent.length;
                    var nodeEnd = currentOffset + nodeLen;
                    if (nodeEnd > startIdx && currentOffset < endIdx) {
                        var range = document.createRange();
                        var localStart = Math.max(0, startIdx - currentOffset);
                        var localEnd = Math.min(nodeLen, endIdx - currentOffset);
                        range.setStart(node, localStart);
                        range.setEnd(node, localEnd);
                        var span = document.createElement('span');
                        span.className = 'tts-highlight';
                        try { range.surroundContents(span); } catch(e) {}
                        if (shouldScroll && currentOffset <= startIdx) {
                            span.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        }
                    }
                    currentOffset = nodeEnd;
                }
            }

            function clearTtsHighlights() {
                document.querySelectorAll('.tts-highlight').forEach(function(el) {
                    var parent = el.parentNode;
                    parent.replaceChild(document.createTextNode(el.textContent), el);
                    parent.normalize();
                });
            }

            function scrollToProgress(progress) {
                var docHeight = document.documentElement.scrollHeight - window.innerHeight;
                window.scrollTo(0, docHeight * progress);
            }

            function scrollToTextOffset(offset) {
                var textLength = Math.max(document.body.textContent.length, 1);
                var docHeight = document.documentElement.scrollHeight - window.innerHeight;
                var progress = Math.max(0, Math.min(1, offset / textLength));
                window.scrollTo({ top: docHeight * progress, behavior: 'smooth' });
            }

            function getTextOffset(container, offset) {
                try {
                    var range = document.createRange();
                    range.setStart(document.body, 0);
                    range.setEnd(container, offset);
                    return range.toString().length;
                } catch(e) {}

                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                var currentOffset = 0;
                var node;
                while (node = walker.nextNode()) {
                    if (node === container) {
                        return currentOffset + offset;
                    }
                    currentOffset += node.textContent.length;
                }
                return currentOffset;
            }

            document.addEventListener('selectionchange', function() {
                var selection = window.getSelection();
                if (selection && selection.rangeCount > 0 && selection.toString().trim().length > 0) {
                    var range = selection.getRangeAt(0);
                    var startOffset = getTextOffset(range.startContainer, range.startOffset);
                    var endOffset = getTextOffset(range.endContainer, range.endOffset);
                    if (startOffset > endOffset) {
                        var temp = startOffset;
                        startOffset = endOffset;
                        endOffset = temp;
                    }
                    if (typeof Android !== 'undefined') Android.onTextSelected(selection.toString(), startOffset, endOffset);
                }
            });
        """.trimIndent()
    }

    data class DocumentData(
        val title: String,
        val author: String,
        val description: String,
        val publisher: String,
        val language: String,
        val chapterCount: Int,
        val coverImage: ByteArray?,
        val type: DocumentType
    )

    fun parseDocument(filePath: String): DocumentData? {
        val type = detectType(filePath)
        return when (type) {
            DocumentType.EPUB -> parseEpub(filePath)
            DocumentType.PDF -> parsePdf(filePath)
            DocumentType.HTML -> parseHtml(filePath)
            DocumentType.UNKNOWN -> null
        }
    }

    private fun parseEpub(filePath: String): DocumentData? {
        val data = epubParser.parseEpub(filePath) ?: return null
        currentType = DocumentType.EPUB
        return DocumentData(
            title = data.title, author = data.author, description = data.description,
            publisher = data.publisher, language = data.language,
            chapterCount = data.chapterCount, coverImage = data.coverImage,
            type = DocumentType.EPUB
        )
    }

    private fun parsePdf(filePath: String): DocumentData? {
        return try {
            if (!pdfBoxInitialized) {
                PDFBoxResourceLoader.init(context)
                pdfBoxInitialized = true
            }

            val doc = PDDocument.load(File(filePath))
            pdfDocument = doc

            val info = doc.documentInformation
            val pageCount = doc.numberOfPages

            val stripper = PDFTextStripper()
            val pages = mutableListOf<String>()
            for (i in 1..pageCount) {
                stripper.startPage = i
                stripper.endPage = i
                val text = stripper.getText(doc).trim()
                if (text.isNotBlank()) {
                    pages.add(text)
                }
            }
            pdfPages = pages
            currentType = DocumentType.PDF
            currentFilePath = filePath

            DocumentData(
                title = info?.title ?: File(filePath).nameWithoutExtension,
                author = info?.author ?: "Unknown Author",
                description = info?.subject ?: "",
                publisher = info?.creator ?: "",
                language = "en",
                chapterCount = pages.size.coerceAtLeast(1),
                coverImage = null,
                type = DocumentType.PDF
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseHtml(filePath: String): DocumentData? {
        return try {
            val file = File(filePath)
            val doc = Jsoup.parse(file, "UTF-8")

            htmlTitle = doc.title().ifBlank { file.nameWithoutExtension }
            htmlContent = doc.body()?.html() ?: doc.html()
            htmlPlainText = doc.body()?.text() ?: doc.text()

            currentType = DocumentType.HTML
            currentFilePath = filePath

            DocumentData(
                title = htmlTitle,
                author = doc.select("meta[name=author]").attr("content").ifBlank { "Unknown Author" },
                description = doc.select("meta[name=description]").attr("content"),
                publisher = "",
                language = doc.select("html").attr("lang").ifBlank { "en" },
                chapterCount = 1,
                coverImage = null,
                type = DocumentType.HTML
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun openDocument(filePath: String): Boolean {
        val type = detectType(filePath)
        return when (type) {
            DocumentType.EPUB -> {
                currentType = DocumentType.EPUB
                epubParser.openBook(filePath)
            }
            DocumentType.PDF -> parsePdf(filePath) != null
            DocumentType.HTML -> parseHtml(filePath) != null
            DocumentType.UNKNOWN -> false
        }
    }

    fun getChapterCount(): Int = when (currentType) {
        DocumentType.EPUB -> epubParser.getChapterCount()
        DocumentType.PDF -> pdfPages.size.coerceAtLeast(1)
        DocumentType.HTML -> 1
        DocumentType.UNKNOWN -> 0
    }

    fun getChapterContent(chapterIndex: Int): ChapterContent? = when (currentType) {
        DocumentType.EPUB -> epubParser.getChapterContent(chapterIndex)
        DocumentType.PDF -> getPdfPageContent(chapterIndex)
        DocumentType.HTML -> getHtmlContent()
        DocumentType.UNKNOWN -> null
    }

    private fun getPdfPageContent(pageIndex: Int): ChapterContent? {
        if (pageIndex < 0 || pageIndex >= pdfPages.size) return null
        val text = pdfPages[pageIndex]
        val html = text.split("\n\n").joinToString("") { para ->
            val escaped = escapeHtml(para)
            "<p>${escaped.replace("\n", "<br>")}</p>"
        }
        return ChapterContent(
            index = pageIndex,
            title = "Page ${pageIndex + 1}",
            htmlContent = html,
            plainText = text
        )
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun getHtmlContent(): ChapterContent? {
        val safelist = org.jsoup.safety.Safelist.relaxed()
            .removeTags("script", "iframe", "object", "embed", "form", "input", "textarea", "button", "applet")
        val cleanHtml = Jsoup.clean(htmlContent ?: "", safelist)
        val cleanDoc = Jsoup.parseBodyFragment(cleanHtml)
        stripRemoteResources(cleanDoc)
        return ChapterContent(
            index = 0,
            title = htmlTitle,
            htmlContent = cleanDoc.body().html(),
            plainText = htmlPlainText
        )
    }

    private fun stripRemoteResources(doc: org.jsoup.nodes.Document) {
        doc.select("[src], [href], [poster]").forEach { el ->
            listOf("src", "href", "poster").forEach { attr ->
                if (isRemoteReference(el.attr(attr))) {
                    el.removeAttr(attr)
                }
            }
        }
        doc.select("[srcset]").forEach { el ->
            if (el.attr("srcset").split(",").any { isRemoteReference(it.trim().substringBefore(" ")) }) {
                el.removeAttr("srcset")
            }
        }
        doc.select("[style]").forEach { el ->
            if (REMOTE_STYLE_URL.containsMatchIn(el.attr("style"))) el.removeAttr("style")
        }
        removeStandaloneOpenControls(doc)
    }

    private fun removeStandaloneOpenControls(doc: org.jsoup.nodes.Document) {
        doc.select("body *").forEach { element ->
            if (element.text().trim().equals("open", ignoreCase = true)) {
                element.remove()
            }
        }
        doc.body()?.childNodes()?.toList()?.forEach { node ->
            if (node is TextNode && node.text().trim().equals("open", ignoreCase = true)) {
                node.remove()
            }
        }
    }

    private fun hasNoUsefulTarget(element: org.jsoup.nodes.Element): Boolean {
        val href = element.attr("href").trim()
        val onclick = element.attr("onclick").trim()
        val deadHref = !element.hasAttr("href") ||
            href.isEmpty() ||
            href == "#" ||
            href.startsWith("javascript:", ignoreCase = true) ||
            isRemoteReference(href)
        val deadAction = !element.hasAttr("onclick") ||
            onclick.isEmpty() ||
            onclick.startsWith("window.open", ignoreCase = true)
        return when {
            element.normalName() == "a" -> deadHref
            element.normalName() == "button" -> deadAction
            element.attr("role").equals("button", ignoreCase = true) -> deadHref && deadAction
            else -> deadHref && deadAction
        }
    }

    private fun isRemoteReference(value: String): Boolean =
        REMOTE_REFERENCE.matches(value.trim())

    fun getChapterHtmlForWebView(
        chapterIndex: Int, fontSize: Float, lineSpacing: Float,
        theme: String, fontFamily: String, margins: Int
    ): String = when (currentType) {
        DocumentType.EPUB -> epubParser.getChapterHtmlForWebView(
            chapterIndex, fontSize, lineSpacing, theme, fontFamily, margins
        )
        DocumentType.PDF, DocumentType.HTML -> {
            val chapter = getChapterContent(chapterIndex)
                ?: return "<html><body><p>Could not load content</p></body></html>"
            wrapInStyledHtml(chapter.htmlContent, fontSize, lineSpacing, theme, fontFamily, margins)
        }
        DocumentType.UNKNOWN -> "<html><body><p>Unsupported format</p></body></html>"
    }

    private fun wrapInStyledHtml(
        bodyHtml: String, fontSize: Float, lineSpacing: Float,
        theme: String, fontFamily: String, margins: Int
    ): String {
        val cleanedBodyHtml = cleanReaderBodyHtml(bodyHtml)
        val (bgColor, textColor, linkColor) = when (theme) {
            "dark" -> Triple("#1A1A2E", "#EAEAEA", "#8B8BFF")
            "sepia" -> Triple("#F4ECD8", "#5B4636", "#8B6914")
            else -> Triple("#FAFAFA", "#1A1A2E", "#6C63FF")
        }

        val fontFamilyCss = when (fontFamily) {
            "serif" -> "Georgia, 'Times New Roman', serif"
            "mono" -> "'Courier New', Courier, monospace"
            else -> "-apple-system, 'Segoe UI', Roboto, sans-serif"
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    background-color: $bgColor; color: $textColor;
                    font-family: $fontFamilyCss; font-size: ${fontSize}px;
                    line-height: $lineSpacing; padding: ${margins}px;
                    word-wrap: break-word; overflow-wrap: break-word;
                    -webkit-text-size-adjust: none;
                }
                a { color: $linkColor; text-decoration: none; }
                img { max-width: 100%; height: auto; display: block; margin: 16px auto; }
                h1, h2, h3, h4, h5, h6 { margin: 24px 0 12px 0; line-height: 1.3; }
                p { margin-bottom: 12px; text-align: justify; }
                blockquote { margin: 16px 0; padding: 8px 16px; border-left: 3px solid $linkColor; opacity: 0.9; }
                .tts-highlight { background-color: rgba(108, 99, 255, 0.3); border-radius: 3px; padding: 1px 2px; transition: background-color 0.2s ease; }
                .user-highlight-yellow { background-color: rgba(255, 235, 59, 0.5); }
                .user-highlight-blue { background-color: rgba(100, 181, 246, 0.5); }
                .user-highlight-green { background-color: rgba(129, 199, 132, 0.5); }
                .user-highlight-pink { background-color: rgba(244, 143, 177, 0.5); }
                ::selection { background-color: rgba(108, 99, 255, 0.4); }
            </style>
            <script>
                $READER_JS
            </script>
        </head>
        <body>$cleanedBodyHtml</body>
        </html>
        """.trimIndent()
    }

    private fun cleanReaderBodyHtml(bodyHtml: String): String {
        val doc = Jsoup.parseBodyFragment(bodyHtml)
        removeStandaloneOpenControls(doc)
        return doc.body().html()
    }

    fun getTableOfContents(): List<TocEntry> = when (currentType) {
        DocumentType.EPUB -> epubParser.getTableOfContents()
        DocumentType.PDF -> pdfPages.mapIndexed { index, _ ->
            TocEntry(title = "Page ${index + 1}", href = "", chapterIndex = index)
        }
        DocumentType.HTML -> listOf(TocEntry(title = htmlTitle, href = "", chapterIndex = 0))
        DocumentType.UNKNOWN -> emptyList()
    }

    fun getPlainTextForChapter(chapterIndex: Int): String = when (currentType) {
        DocumentType.EPUB -> epubParser.getPlainTextForChapter(chapterIndex)
        DocumentType.PDF -> pdfPages.getOrNull(chapterIndex) ?: ""
        DocumentType.HTML -> htmlPlainText
        DocumentType.UNKNOWN -> ""
    }

    fun close() {
        epubParser.close()
        try { pdfDocument?.close() } catch (_: Exception) {}
        pdfDocument = null
        pdfPages = emptyList()
        htmlContent = null
        currentType = DocumentType.UNKNOWN
        currentFilePath = null
    }
}
