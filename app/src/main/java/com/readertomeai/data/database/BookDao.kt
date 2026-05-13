package com.readertomeai.data.database

import androidx.room.*
import com.readertomeai.data.model.Book
import com.readertomeai.data.model.Bookmark
import com.readertomeai.data.model.Highlight
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    // Books
    @Query("SELECT * FROM books ORDER BY isFinished ASC, isFavorite DESC, lastReadAt DESC, addedAt DESC")
    fun getAllBooksByRecent(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY isFinished ASC, isFavorite DESC, title COLLATE NOCASE ASC")
    fun getAllBooksByTitle(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY isFinished ASC, isFavorite DESC, author COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun getAllBooksByAuthor(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY isFinished ASC, isFavorite DESC, addedAt DESC")
    fun getAllBooksByAdded(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): Book?

    @Query("SELECT * FROM books WHERE filePath = :path LIMIT 1")
    suspend fun getBookByPath(path: String): Book?

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' ORDER BY isFinished ASC, isFavorite DESC, lastReadAt DESC, title COLLATE NOCASE ASC")
    fun searchBooks(query: String): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("""
        UPDATE books
        SET currentChapter = :chapter,
            currentPosition = :position,
            overallProgress = :progress,
            lastReadAt = :timestamp,
            isFinished = CASE WHEN :progress >= 0.995 THEN 1 ELSE isFinished END,
            finishedAt = CASE WHEN :progress >= 0.995 AND finishedAt IS NULL THEN :timestamp ELSE finishedAt END
        WHERE id = :bookId
    """)
    suspend fun updateReadingProgress(bookId: Long, chapter: Int, position: Float, progress: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

    @Query("UPDATE books SET isFinished = :isFinished, finishedAt = :finishedAt WHERE id = :bookId")
    suspend fun updateFinished(bookId: Long, isFinished: Boolean, finishedAt: Long?)

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, position ASC")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    // Highlights
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startOffset ASC")
    fun getHighlightsForBook(bookId: Long): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
    suspend fun getHighlightsForChapter(bookId: Long, chapterIndex: Int): List<Highlight>

    @Insert
    suspend fun insertHighlight(highlight: Highlight): Long

    @Update
    suspend fun updateHighlight(highlight: Highlight)

    @Delete
    suspend fun deleteHighlight(highlight: Highlight)
}
