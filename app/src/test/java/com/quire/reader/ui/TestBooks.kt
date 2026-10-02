package com.quire.reader.ui

import com.quire.reader.data.Book
import com.quire.reader.data.BookStatus

/** Builds a [Book] with sensible defaults so each test only states what it cares about. */
fun testBook(
  id: Long,
  title: String,
  author: String = "Some Author",
  series: String? = null,
  seriesNo: Double? = null,
  tags: List<String> = emptyList(),
  status: BookStatus = BookStatus.Unread,
  progress: Float = 0f,
  lastOpened: Long = 0,
  addedAt: Long = id * 1000,
  year: Int? = null,
  pages: Int = 200,
  size: Long = 500_000,
  rating: Int = 0,
  isNew: Boolean = false,
) = Book(
  id = id, path = "/books/$id.epub", folderId = 1, title = title, sortTitle = title.lowercase(), author = author, primaryAuthor = author,
  authorSort = author.split(" ").let { if (it.size > 1) "${it.last()}, ${it.dropLast(1).joinToString(" ")}" else author }.lowercase(),
  series = series, seriesNo = seriesNo, year = year, pages = pages, tags = tags, userTags = emptyList(), language = "en", rating = rating,
  status = status, progress = progress, lastOpened = lastOpened, addedAt = addedAt, sizeBytes = size, desc = null, coverPath = null,
  fromCalibre = false, readable = true, isNew = isNew,
)
