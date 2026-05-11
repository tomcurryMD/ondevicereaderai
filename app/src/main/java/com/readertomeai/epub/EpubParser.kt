package com.readertomeai.epub

import android.content.Context
import android.util.Base64
import com.readertomeai.data.model.ChapterContent
import com.readertomeai.data.model.TocEntry
import nl.siegmann.epublib.domain.Book as EpubBook
import nl.siegmann.epublib.domain.Resource
import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.File
import java.io.FileInputStream

data class EpubData(
    val title: String,
    val author: String,
    val description: String,
    val publisher: String,
    val language: String,
    val chapterCount: Int,
    val coverImage: ByteArray?
)

class EpubParser(private val context: Context) {

    private var currentBook: EpubBook? = null
    private var currentFilePath: String? = null

    /**
     * Safelist that allows common EPUB HTML tags but strips <script>, <iframe>, etc.
     */
    private val epubSafelist = Safelist.relaxed()
        .addTags("section", "article", "aside", "header", "footer", "nav", "figure", "figcaption", "details", "summary")
        .addAttributes(":all", "id", "class", "style", "lang", "dir", "role", "aria-label")
        .removeTags("script", "iframe", "object", "embed", "form", "input", "textarea", "button", "applet")

    fun parseEpub(filePath: String): EpubData? {
        return try {
            val reader = EpubReader()
            val book = reader.readEpub(FileInputStream(File(filePath)))
            currentBook = book
            currentFilePath = filePath

            val metadata = book.metadata

            val title = metadata.firstTitle ?: ""
            val author = metadata.authors.joinToString(", ") {
                "${it.firstname} ${it.lastname}".trim()
            }
            val description = metadata.descriptions.firstOrNull() ?: ""
            val publisher = metadata.publishers.firstOrNull() ?: ""
            val language = metadata.language ?: "en"

            val coverImage = try {
                book.coverImage?.data
            } catch (e: Exception) { null }

            val chapterCount = book.spine.spineReferences.size

            EpubData(
                title = title, author = author, description = description,
                publisher = publisher, language = language,
                chapterCount = chapterCount, coverImage = coverImage
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun openBook(filePath: String): Boolean {
        return try {
            if (currentFilePath != filePath) {
                val reader = EpubReader()
                currentBook = reader.readEpub(FileInputStream(File(filePath)))
                currentFilePath = filePath
            }
            currentBook != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getChapterCount(): Int = currentBook?.spine?.spineReferences?.size ?: 0

    fun getChapterContent(chapterIndex: Int): ChapterContent? {
        val book = currentBook ?: return null
        val spineRefs = book.spine.spineReferences
        if (chapterIndex < 0 || chapterIndex >= spineRefs.size) return null

        return try {
            val resource = spineRefs[chapterIndex].resource
            val rawHtml = String(resource.data, Charsets.UTF_8)

            val doc = Jsoup.parse(rawHtml)

            // Sanitize: remove script/iframe/form tags from book content
            val cleanBody = Jsoup.clean(doc.body()?.html() ?: rawHtml, epubSafelist)

            val title = doc.select("h1, h2, h3").firstOrNull()?.text()
                ?: "Chapter ${chapterIndex + 1}"

            // Inline images as base64 data URIs so they render without file access
            val cleanDoc = Jsoup.parseBodyFragment(cleanBody)
            resolveImages(cleanDoc, book)
            // Strip any remaining remote resource references for privacy
            stripRemoteResources(cleanDoc)

            val plainText = cleanDoc.text()

            ChapterContent(
                index = chapterIndex,
                title = title,
                htmlContent = cleanDoc.body().html(),
                plainText = plainText
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Resolves relative image src attributes by inlining the image data as base64 data URIs.
     * This avoids needing file:// access in the WebView.
     */
    private fun resolveImages(doc: org.jsoup.nodes.Document, book: EpubBook) {
        doc.select("img[src]").forEach { img ->
            val src = img.attr("src")
            if (!src.startsWith("data:")) {
                try {
                    // Try to find the resource in the EPUB
                    val resource = findResource(book, src)
                    if (resource != null) {
                        val mimeType = resource.mediaType?.name ?: "image/png"
                        val base64 = Base64.encodeToString(resource.data, Base64.NO_WRAP)
                        img.attr("src", "data:$mimeType;base64,$base64")
                    }
                } catch (e: Exception) {
                    // If we can't resolve, remove the broken image
                    img.attr("alt", "[Image]")
                    img.removeAttr("src")
                }
            }
        }
    }

    private fun findResource(book: EpubBook, href: String): Resource? {
        // Try direct href
        var resource = book.resources.getByHref(href)
        if (resource != null) return resource

        // Try without leading ../
        val cleaned = href.replace(Regex("^(\\.\\./)+"), "")
        resource = book.resources.getByHref(cleaned)
        if (resource != null) return resource

        // Try matching just the filename
        val filename = href.substringAfterLast("/")
        return book.resources.all.firstOrNull {
            it.href.endsWith(filename)
        }
    }

    /**
     * Remove any remaining remote resource references (http/https URLs in src, href, etc.)
     * to prevent privacy leaks from book content loading external resources.
     */
    private fun stripRemoteResources(doc: org.jsoup.nodes.Document) {
        // Strip remote images
        doc.select("img[src~=^https?://]").forEach { it.remove() }
        // Strip remote stylesheets
        doc.select("link[href~=^https?://]").forEach { it.remove() }
        // Strip remote audio/video
        doc.select("audio[src~=^https?://], video[src~=^https?://], source[src~=^https?://]").forEach { it.remove() }
        // Strip any element with background-image pointing to remote URL
        doc.select("[style*=url]").forEach { el ->
            val style = el.attr("style")
            if (style.contains("http://") || style.contains("https://")) {
                el.removeAttr("style")
            }
        }
    }

    fun getChapterHtmlForWebView(
        chapterIndex: Int, fontSize: Float, lineSpacing: Float,
        theme: String, fontFamily: String, margins: Int
    ): String {
        val chapter = getChapterContent(chapterIndex)
            ?: return "<html><body><p>Could not load chapter</p></body></html>"

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
                // Trusted app-level JavaScript (not from book content)
                var lastReportedProgress = -1;
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

                function highlightSentence(startIdx, endIdx) {
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
                            if (currentOffset <= startIdx) {
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

                document.addEventListener('selectionchange', function() {
                    var selection = window.getSelection();
                    if (selection && selection.toString().trim().length > 0) {
                        if (typeof Android !== 'undefined') Android.onTextSelected(selection.toString());
                    }
                });
            </script>
        </head>
        <body>${chapter.htmlContent}</body>
        </html>
        """.trimIndent()
    }

    fun getTableOfContents(): List<TocEntry> {
        val book = currentBook ?: return emptyList()
        val toc = mutableListOf<TocEntry>()
        val spineRefs = book.spine.spineReferences

        spineRefs.forEachIndexed { index, ref ->
            val resource = ref.resource
            val title = try {
                val doc = Jsoup.parse(String(resource.data, Charsets.UTF_8))
                doc.select("h1, h2, h3").firstOrNull()?.text() ?: "Chapter ${index + 1}"
            } catch (e: Exception) { "Chapter ${index + 1}" }
            toc.add(TocEntry(title = title, href = resource.href, chapterIndex = index))
        }
        return toc
    }

    fun getPlainTextForChapter(chapterIndex: Int): String {
        return getChapterContent(chapterIndex)?.plainText ?: ""
    }

    fun close() {
        currentBook = null
        currentFilePath = null
    }
}
