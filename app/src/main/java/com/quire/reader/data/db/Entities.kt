package com.quire.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder the user chose to keep in the library. */
@Entity(tableName = "folder", indices = [Index("path", unique = true)])
data class FolderEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val path: String,
  val watched: Boolean = true,
  val lastScanAt: Long = 0,
)

/** One EPUB file plus the metadata read from Calibre's `metadata.opf` or the EPUB itself. */
@Entity(
  tableName = "book",
  indices = [Index("path", unique = true), Index("folderId")],
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
  /** `calibre` tags are replaced on rescan; `user` tags are Quire's own and survive. */
  val origin: String,
) {
  companion object {
    const val ORIGIN_CALIBRE = "calibre"
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
)

/**
 * One searchable passage of a book's text. [text] is the primary passage followed by up to 63 tokens of
 * the text after it (so phrases that straddle a chunk boundary still match); [primaryEndByte] is the
 * UTF-8 length of the primary part, which decides which chunk owns a match.
 */
@Entity(
  tableName = "text_chunk",
  indices = [Index(value = ["bookId", "seq"], unique = true)],
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class TextChunkEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bookId: Long,
  /** Position of the chunk within the book, 0-based. */
  val seq: Int,
  val chapter: String,
  /** EPUB resource this chunk was read from. */
  val href: String,
  /** Canonical source token range of the primary part (not the repeated context). */
  val tokenStart: Int,
  val tokenEnd: Int,
  val primaryEndByte: Int,
  val text: String,
  /** JSON associating UTF-8 ranges of [text] with their source elements' locators and text ranges. */
  val mapping: String,
  /** Nearest known progression through the publication, 0..1. */
  val progression: Double,
)

/** Full-text index over [TextChunkEntity.text]; Room keeps it in step with `text_chunk` through triggers. */
@Fts4(contentEntity = TextChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "text_chunk_fts")
data class TextChunkFts(
  @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
  val text: String,
)

/** The outcome of indexing a book, tied to the file signature (`mtime`, `sizeBytes`) it was attempted against. */
@Entity(
  tableName = "index_state",
  foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class IndexStateEntity(
  @PrimaryKey val bookId: Long,
  val mtime: Long,
  val sizeBytes: Long,
  val status: String,
  val completedAt: Long,
  val chunkCount: Int = 0,
  /** UTF-8 bytes of chunk text persisted for the book. */
  val textBytes: Long = 0,
  /** True when the per-book size cap stopped indexing before the end of the book. */
  val truncated: Boolean = false,
  /** HTML resources of the book that could not be read (damaged entries); their text is missing from the index. */
  @ColumnInfo(defaultValue = "0") val unreadableResources: Int = 0,
) {
  companion object {
    const val STATUS_DONE = "done"
    /** The publication could not be opened or is DRM-protected. */
    const val STATUS_FAILED = "failed"
    /** The publication opened but has no extractable text. */
    const val STATUS_SKIPPED = "skipped"
  }
}
