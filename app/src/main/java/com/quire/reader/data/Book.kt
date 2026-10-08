package com.quire.reader.data

import androidx.compose.ui.graphics.Color
import com.quire.reader.data.db.BookStateEntity
import com.quire.reader.data.db.CatalogRow
import com.quire.reader.data.db.ReadingStateRow
import com.quire.reader.theme.Nq
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class BookStatus { Reading, Unread, Finished }

/** Painted cover ground (a gradient pair) used when a book has no cover image. */
enum class Ground(val from: Color, val to: Color) {
  A(Nq.section, Nq.neutral900),
  B(Nq.neutral800, Nq.neutral900),
  C(Nq.accent800, Nq.section),
  D(Nq.sectionGlow, Nq.neutral900),
  E(Nq.neutral700, Nq.neutral900),
  F(Nq.accent900, Nq.neutral900);

  companion object {
    /** Stable per title, so a book keeps its colour from one launch to the next. */
    fun forTitle(title: String): Ground = entries[abs(title.hashCode()) % entries.size]
  }
}

/** A book as the UI sees it: database row + reading state, with display helpers. */
data class Book(
  val id: Long,
  val path: String,
  val folderId: Long,
  val title: String,
  val sortTitle: String,
  val author: String,
  val primaryAuthor: String,
  val authorSort: String,
  val series: String?,
  val seriesNo: Double?,
  val year: Int?,
  val pages: Int,
  val tags: List<String>,
  /** The subset of [tags] added inside Quire (these can be removed again). */
  val userTags: List<String>,
  val language: String?,
  /** The user's rating if they set one, otherwise Calibre's (0–5). */
  val rating: Int,
  val status: BookStatus,
  /** 0..1 across the whole book. */
  val progress: Float,
  /** Epoch millis; 0 = never opened. */
  val lastOpened: Long,
  val addedAt: Long,
  val sizeBytes: Long,
  val desc: String?,
  val coverPath: String?,
  val fromCalibre: Boolean,
  val readable: Boolean,
  /** Added recently and not started yet. */
  val isNew: Boolean,
) {
  val pct: Int get() = (progress * 100).roundToInt().coerceIn(0, 100)
  val authorLast: String get() = if (primaryAuthor == "Unknown author") "Unknown" else primaryAuthor.lastWord()
  val ground: Ground = Ground.forTitle(title)

  val sizeLabel: String get() = if (sizeBytes >= 1024 * 1024) "%.1f MB".format(Locale.US, sizeBytes / 1048576.0) else "${(sizeBytes / 1024).coerceAtLeast(1)} KB"
  val addedLabel: String get() = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(Instant.ofEpochMilli(addedAt).atZone(ZoneId.systemDefault()))
  val languageLabel: String get() {
    val code = language?.takeIf { it.isNotBlank() } ?: return "—"
    val base = code.substringBefore('-').lowercase()
    val match = Locale.getAvailableLocales().firstOrNull { it.language == base || runCatching { it.isO3Language }.getOrNull() == base }
    return match?.getDisplayLanguage(Locale.ENGLISH)?.takeIf { it.isNotBlank() } ?: code
  }
  val seriesNoLabel: String? get() = seriesNo?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

  /** "about 3h 20m left", from the page count at [MINUTES_PER_PAGE]. */
  fun timeLeftLabel(): String {
    val minutes = (pages * (1 - progress) * MINUTES_PER_PAGE).roundToInt().coerceAtLeast(1)
    return when {
      minutes < 60 -> "about ${minutes}m left"
      minutes % 60 == 0 -> "about ${minutes / 60}h left"
      else -> "about ${minutes / 60}h ${minutes % 60}m left"
    }
  }

  companion object {
    /** A "page" is one of Readium's positions, about 1,000 characters of text: roughly 170 words, 45 seconds. */
    const val MINUTES_PER_PAGE = 0.75
  }
}

fun String.lastWord() = trim().split(' ').last()

/** Books added in the last 30 days count as "recently added". */
const val RECENT_DAYS = 30L
private const val DAY_MS = 24L * 60 * 60 * 1000

private fun isNew(status: BookStatus, addedAt: Long, now: Long) = status == BookStatus.Unread && now - addedAt < RECENT_DAYS * DAY_MS

/**
 * The book as the library shows it before it is ever opened: unread, Calibre's rating. [withReadingState] adds what
 * the reader saved. Mapped once per catalogue change, since positions are saved far more often.
 */
fun CatalogRow.toBook(now: Long): Book = Book(
  id = id, path = path, folderId = folderId, title = title, sortTitle = sortTitle, author = author, primaryAuthor = primaryAuthor,
  authorSort = authorSort, series = series, seriesNo = seriesIndex, year = pubYear, pages = pageEstimate, tags = tagList, userTags = userTagList, language = language,
  rating = calibreRating, status = BookStatus.Unread, progress = 0f, lastOpened = 0L, addedAt = addedAt,
  sizeBytes = sizeBytes, desc = description, coverPath = coverPath, fromCalibre = source == "calibre", readable = readable,
  isNew = isNew(BookStatus.Unread, addedAt, now),
)

/**
 * This book with its reading state [state] (none: never opened). Only for a book straight from [CatalogRow.toBook]:
 * its rating must still be Calibre's, which a user rating replaces.
 */
fun Book.withReadingState(state: ReadingStateRow?, now: Long): Book {
  val status = when (state?.status) {
    BookStateEntity.STATUS_READING -> BookStatus.Reading
    BookStateEntity.STATUS_FINISHED -> BookStatus.Finished
    else -> BookStatus.Unread
  }
  val new = isNew(status, addedAt, now)
  if (state == null && new == isNew) return this
  return copy(
    rating = state?.userRating ?: rating, status = status, progress = state?.progress ?: 0f, lastOpened = state?.lastOpenedAt ?: 0L,
    isNew = new,
  )
}
