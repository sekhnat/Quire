package com.quire.reader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder the user chose to keep in the library. */
@Entity(tableName = "folder", indices = [Index("path", unique = true)])
data class FolderEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val path: String,
  /**
   * False once the user removed the folder from the library. The row stays while missing books that keep reading history
   * still belong to it, so adding the folder again brings them back.
   */
  val watched: Boolean = true,
  val lastScanAt: Long = 0,
)

/** One EPUB file plus the metadata read from Calibre's `metadata.opf` or the EPUB itself. */
@Entity(
  tableName = "book",
  indices = [Index("path", unique = true), Index("folderId"), Index("calibreUuid"), Index("epubUid"), Index("fingerprint")],
  foreignKeys = [ForeignKey(FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE)],
)
data class BookEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val path: String,
  val folderId: Long,
  val sizeBytes: Long,
  val mtime: Long,
  val title: String,
  val sortTitle: String,
  /** Display name; several authors are joined with " & ". */
  val author: String,
  /** First author, used to group the Authors tab. */
  val primaryAuthor: String,
  val authorSort: String,
  val series: String? = null,
  val seriesIndex: Double? = null,
  val pubYear: Int? = null,
  val language: String? = null,
  val description: String? = null,
  /** Calibre rating on a 0–5 scale (Calibre stores 0–10). */
  val calibreRating: Int = 0,
  val addedAt: Long,
  val pageEstimate: Int = 0,
  val coverPath: String? = null,
  /** `calibre` when metadata came from a metadata.opf, otherwise `file`. */
  val source: String = SOURCE_FILE,
  /** False when the EPUB could not be opened (corrupt, DRM). */
  val readable: Boolean = true,
  /** Identity keys that let a moved or renamed file keep this row; see [com.quire.reader.data.scan.BookIdentity]. */
  val calibreUuid: String? = null,
  val epubUid: String? = null,
  val fingerprint: String? = null,
  /**
   * When the file was last seen missing; null while the book is in the library. A missing book is hidden everywhere but
   * keeps its reading history, which reattaches when the file is found again at its path or under its identity.
   */
  val missingSince: Long? = null,
  /**
   * Words in the book's text, counted in the background by [com.quire.reader.data.scan.WordCountWorker] while the word
   * count setting is on; null until then, 0 when the text could not be read. Cleared when the file changes.
   */
  val wordCount: Int? = null,
) {
  companion object {
    const val SOURCE_CALIBRE = "calibre"
    const val SOURCE_FILE = "file"
  }
}

@Entity(
  tableName = "book_tag",
  primaryKeys = ["bookId", "tag"],
  indices = [Index("tag")],
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookTagEntity(
  val bookId: Long,
  val tag: String,
  /**
   * `calibre` tags (read from the book or its metadata.opf) are replaced on rescan; `folder` is the one tag naming the
   * folder the file was found in (see [com.quire.reader.data.scan.FolderTags]), kept in step by every scan; `user` tags
   * are Quire's own and survive.
   */
  val origin: String,
) {
  companion object {
    const val ORIGIN_CALIBRE = "calibre"
    const val ORIGIN_FOLDER = "folder"
    const val ORIGIN_USER = "user"
  }
}

/** Everything Quire itself knows about a book: reading position, status and the user's own edits. */
@Entity(
  tableName = "book_state",
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookStateEntity(
  @PrimaryKey val bookId: Long,
  val locatorJson: String? = null,
  /** 0..1 across the whole book. */
  val progress: Float = 0f,
  val status: String = STATUS_UNREAD,
  val lastOpenedAt: Long = 0,
  val finishedAt: Long = 0,
  /** Rating set inside Quire (overrides Calibre's when present). */
  val userRating: Int? = null,
  /** Per-book reader settings as JSON, null = use the defaults. */
  val prefsJson: String? = null,
) {
  companion object {
    const val STATUS_UNREAD = "unread"
    const val STATUS_READING = "reading"
    const val STATUS_FINISHED = "finished"
  }
}

@Entity(
  tableName = "bookmark",
  indices = [Index("bookId")],
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookmarkEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bookId: Long,
  val locatorJson: String,
  /** Chapter title and progress for display in the list. */
  val label: String,
  val progress: Float,
  val createdAt: Long,
)

@Entity(
  tableName = "highlight",
  indices = [Index("bookId")],
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class HighlightEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bookId: Long,
  val locatorJson: String,
  val text: String,
  val note: String? = null,
  val progress: Float,
  val createdAt: Long,
  /** Index into `HighlightColor`; 0 is the violet every highlight had before there was a choice. */
  val color: Int = 0,
)

/**
 * One stretch of reading: from the reader coming to the front until it left it. [activeMs] is the time the reader
 * was actually being read (idle gaps are capped, see `ReadingClock`), so it is usually shorter than [endedAt] - [startedAt].
 */
@Entity(
  tableName = "reading_session",
  indices = [Index("bookId"), Index("startedAt")],
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ReadingSessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bookId: Long,
  val startedAt: Long,
  val endedAt: Long,
  val activeMs: Long,
  /** 0..1 across the whole book, where the session started and ended. */
  val startProgress: Float,
  val endProgress: Float,
  /** How much of the book was read page by page, as a fraction of it: forward steps only, jumps left out. */
  val readProgress: Float,
)

/**
 * The user's own title, authors, series and cover for a book, made in Quire. Each field is null while it is not edited,
 * so the file's (or Calibre's) value shows. Scans never read or write this table: an edit outlives every rescan,
 * whatever the file later says. The sort keys are worked out from the edited values when they are saved (see
 * [com.quire.reader.data.BookEdits]), so queries that sort and filter by them need no Kotlin.
 */
@Entity(
  tableName = "book_edit",
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookEditEntity(
  @PrimaryKey val bookId: Long,
  val title: String? = null,
  val sortTitle: String? = null,
  /** Several authors joined with " & ", as the scanner shows them. */
  val author: String? = null,
  val primaryAuthor: String? = null,
  val authorSort: String? = null,
  /** An empty string is "no series" (the edit took the book out of the one its file names). */
  val series: String? = null,
  val seriesIndex: Double? = null,
  /** A cover the user chose, in the covers folder. */
  val coverPath: String? = null,
  val updatedAt: Long = 0,
) {
  val isEmpty: Boolean get() = title == null && author == null && series == null && seriesIndex == null && coverPath == null
}
