package com.readertomeai.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.readertomeai.data.database.BookDao
import com.readertomeai.data.model.*
import com.readertomeai.epub.DocumentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BookRepository(
    private val bookDao: BookDao,
    private val context: Context
) {

    fun getBooksByRecent(): Flow<List<Book>> = bookDao.getAllBooksByRecent()
    fun getBooksByTitle(): Flow<List<Book>> = bookDao.getAllBooksByTitle()
    fun getBooksByAuthor(): Flow<List<Book>> = bookDao.getAllBooksByAuthor()
    fun getBooksByAdded(): Flow<List<Book>> = bookDao.getAllBooksByAdded()
    fun searchBooks(query: String): Flow<List<Book>> = bookDao.searchBooks(query)

    suspend fun getBook(bookId: Long): Book? = bookDao.getBookById(bookId)

    suspend fun importBook(uri: Uri): Book? = withContext(Dispatchers.IO) {
        try {
            // Determine file extension from URI
            val mimeType = context.contentResolver.getType(uri)
            val extension = when {
                mimeType == "application/epub+zip" -> ".epub"
                mimeType == "application/pdf" -> ".pdf"
                mimeType == "text/html" || mimeType == "application/xhtml+xml" -> ".html"
                else -> {
                    // Try to guess from URI path
                    val path = uri.path ?: ""
                    when {
                        path.endsWith(".epub", ignoreCase = true) -> ".epub"
                        path.endsWith(".pdf", ignoreCase = true) -> ".pdf"
                        path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true) -> ".html"
                        else -> ".epub" // default fallback
                    }
                }
            }

            // Copy file to internal storage
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val booksDir = File(context.filesDir, "books").apply { mkdirs() }
            val fileName = "book_${System.currentTimeMillis()}$extension"
            val destFile = File(booksDir, fileName)

            destFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            // Parse the document (ePub, PDF, or HTML)
            val parser = DocumentParser(context)
            val docData = parser.parseDocument(destFile.absolutePath) ?: return@withContext null

            // Save cover image if available
            val coverPath = docData.coverImage?.let { coverBytes ->
                val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                val coverFile = File(coversDir, "cover_${System.currentTimeMillis()}.jpg")
                try {
                    val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                    FileOutputStream(coverFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                    }
                    bitmap.recycle()
                    coverFile.absolutePath
                } catch (e: Exception) {
                    null
                }
            }

            // Check if book already exists
            val existing = bookDao.getBookByPath(destFile.absolutePath)
            if (existing != null) {
                parser.close()
                return@withContext existing
            }

            val book = Book(
                title = docData.title.ifBlank { "Unknown Title" },
                author = docData.author.ifBlank { "Unknown Author" },
                filePath = destFile.absolutePath,
                coverPath = coverPath,
                totalChapters = docData.chapterCount,
                fileSize = destFile.length(),
                language = docData.language,
                description = docData.description,
                publisher = docData.publisher
            )

            parser.close()
            val id = bookDao.insertBook(book)
            book.copy(id = id)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun updateProgress(bookId: Long, chapter: Int, position: Float, progress: Float) {
        bookDao.updateReadingProgress(
            bookId = bookId,
            chapter = chapter,
            position = position.coerceIn(0f, 1f),
            progress = progress.coerceIn(0f, 1f)
        )
    }

    suspend fun setFavorite(bookId: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        bookDao.updateFavorite(bookId, isFavorite)
    }

    suspend fun setFinished(bookId: Long, isFinished: Boolean) = withContext(Dispatchers.IO) {
        bookDao.updateFinished(
            bookId = bookId,
            isFinished = isFinished,
            finishedAt = if (isFinished) System.currentTimeMillis() else null
        )
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        // Delete physical files
        book.filePath.let { File(it).delete() }
        book.coverPath?.let { File(it).delete() }
        bookDao.deleteBook(book)
    }

    // Bookmarks
    fun getBookmarks(bookId: Long): Flow<List<Bookmark>> = bookDao.getBookmarksForBook(bookId)

    suspend fun addBookmark(bookmark: Bookmark): Long = bookDao.insertBookmark(bookmark)

    suspend fun removeBookmark(bookmark: Bookmark) = bookDao.deleteBookmark(bookmark)

    // Highlights
    fun getHighlights(bookId: Long): Flow<List<Highlight>> = bookDao.getHighlightsForBook(bookId)

    suspend fun getHighlightsForChapter(bookId: Long, chapterIndex: Int): List<Highlight> =
        bookDao.getHighlightsForChapter(bookId, chapterIndex)

    suspend fun addHighlight(highlight: Highlight): Long = bookDao.insertHighlight(highlight)

    suspend fun updateHighlight(highlight: Highlight) = bookDao.updateHighlight(highlight)

    suspend fun removeHighlight(highlight: Highlight) = bookDao.deleteHighlight(highlight)
}
