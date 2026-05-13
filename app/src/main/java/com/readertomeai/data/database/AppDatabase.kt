package com.readertomeai.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.readertomeai.data.model.Book
import com.readertomeai.data.model.Bookmark
import com.readertomeai.data.model.Highlight

@Database(
    entities = [Book::class, Bookmark::class, Highlight::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from v1 (no foreign keys) to v2 (with foreign keys + indices).
         * Since adding foreign keys requires recreating tables, we create new tables,
         * copy data, and drop the old ones.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate bookmarks with foreign key
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bookmarks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        position REAL NOT NULL,
                        label TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("INSERT INTO bookmarks_new SELECT * FROM bookmarks")
                db.execSQL("DROP TABLE bookmarks")
                db.execSQL("ALTER TABLE bookmarks_new RENAME TO bookmarks")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")

                // Recreate highlights with foreign key
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS highlights_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        startOffset INTEGER NOT NULL,
                        endOffset INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        color TEXT NOT NULL DEFAULT 'yellow',
                        note TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("INSERT INTO highlights_new SELECT * FROM highlights")
                db.execSQL("DROP TABLE highlights")
                db.execSQL("ALTER TABLE highlights_new RENAME TO highlights")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_bookId ON highlights(bookId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN isFinished INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN finishedAt INTEGER DEFAULT NULL")
                db.execSQL("""
                    UPDATE books
                    SET isFinished = 1,
                        finishedAt = CASE WHEN lastReadAt > 0 THEN lastReadAt ELSE addedAt END
                    WHERE overallProgress >= 0.995
                """)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "readertomeai.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
