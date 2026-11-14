package com.quire.reader.data

import com.quire.reader.data.db.BookEditEntity
import com.quire.reader.data.scan.LibraryScanner
import com.quire.reader.data.scan.LibraryScanner.Companion.UNKNOWN_AUTHOR

/** A field of a book the user can edit in Quire. */
enum class EditField { Title, Authors, Series, SeriesIndex }

/** What the user typed for a field could not be saved, and why. */
class EditRejected(message: String) : Exception(message)

/**
 * The rules for the user's edits (spec feature 11): what each field accepts, and the sort keys worked out from it with
 * the scanner's own functions, so an edited book sorts and groups exactly as a book whose file said the same would.
 */
object BookEdits {

  /**
   * [edit] with [field] set to what the user typed in [value], or cleared when [value] is null. Throws [EditRejected]
   * for a blank title (clear it instead) or a series number that is not a decimal of at least 0 with at most 2 places.
   */
  fun apply(edit: BookEditEntity, field: EditField, value: String?, now: Long): BookEditEntity {
    val v = value?.trim()?.replace(Regex("\\s+"), " ")
    val next = when (field) {
      EditField.Title -> when {
        v == null -> edit.copy(title = null, sortTitle = null)
        v.isEmpty() -> throw EditRejected("A title can't be empty")
        else -> edit.copy(title = v, sortTitle = LibraryScanner.sortKey(v))
      }
      EditField.Authors -> if (v == null) edit.copy(author = null, primaryAuthor = null, authorSort = null) else {
        val authors = splitAuthors(v)
        val primary = authors.firstOrNull() ?: UNKNOWN_AUTHOR
        edit.copy(author = authors.joinToString(" & ").ifEmpty { UNKNOWN_AUTHOR }, primaryAuthor = primary, authorSort = LibraryScanner.authorSortFrom(primary).lowercase())
      }
      // An empty series takes the book out of its series; its number goes with it.
      EditField.Series -> if (v == null) edit.copy(series = null) else edit.copy(series = v, seriesIndex = if (v.isEmpty()) null else edit.seriesIndex)
      EditField.SeriesIndex -> edit.copy(seriesIndex = v?.let(::parseSeriesIndex))
    }
    return next.copy(updatedAt = now)
  }

  /** Authors typed in one field, separated by `&` or `;` (and " and " between two names is left alone: it can be a name). */
  fun splitAuthors(text: String): List<String> = text.split('&', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

  /** "2.5" → 2.5; a comma works as the decimal point too. */
  fun parseSeriesIndex(text: String): Double {
    val n = text.replace(',', '.').toDoubleOrNull() ?: throw EditRejected("The number in the series must be a number, like 2 or 2.5")
    if (n < 0 || n.isNaN() || n.isInfinite()) throw EditRejected("The number in the series can't be negative")
    if (Math.round(n * 100) / 100.0 != n) throw EditRejected("The number in the series can have at most 2 decimals")
    return n
  }

  /** Whether [field] is edited in [edit]. */
  fun isEdited(edit: BookEditEntity?, field: EditField): Boolean = when (field) {
    EditField.Title -> edit?.title != null
    EditField.Authors -> edit?.author != null
    EditField.Series -> edit?.series != null
    EditField.SeriesIndex -> edit?.seriesIndex != null
  }
}
