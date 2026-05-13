package com.readertomeai.data.model

import androidx.room.*

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String? = null,
    val totalChapters: Int = 0,
    val currentChapter: Int = 0,
    val currentPosition: Float = 0f, // 0.0 to 1.0 within chapter (scroll position)
    val overallProgress: Float = 0f, // 0.0 to 1.0 overall
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    val isFavorite: Boolean = false,
    val isFinished: Boolean = false,
    val finishedAt: Long? = null,
    val fileSize: Long = 0,
    val language: String = "en",
    val description: String = "",
    val publisher: String = ""
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val position: Float, // scroll position within chapter (0.0 to 1.0)
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class Highlight(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: String = "yellow", // yellow, blue, green, pink
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class TocEntry(
    val title: String,
    val href: String,
    val chapterIndex: Int,
    val level: Int = 0
)

data class ChapterContent(
    val index: Int,
    val title: String,
    val htmlContent: String,
    val plainText: String
)
