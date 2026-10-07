package com.quire.reader.ui.detail

import com.quire.reader.ui.ReaderRequest
import com.quire.reader.ui.RecordingNavigator
import com.quire.reader.ui.RecordingToasts
import com.quire.reader.ui.Scope
import com.quire.reader.ui.ScopeKind
import com.quire.reader.ui.Visit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailStateTest {
  private class RecordingEditor : BookEditor {
    val edits = mutableListOf<String>()
    override suspend fun setFinished(bookId: Long, finished: Boolean) { edits += "finished $bookId $finished" }
    override suspend fun setUserRating(bookId: Long, rating: Int?) { edits += "rating $bookId $rating" }
    override suspend fun addTag(bookId: Long, tag: String) { edits += "add $bookId $tag" }
    override suspend fun removeTag(bookId: Long, tag: String) { edits += "remove $bookId $tag" }
  }

  private val editor = RecordingEditor()
  private val nav = RecordingNavigator()
  private val toasts = RecordingToasts()

  private fun TestScope.detail(bookId: Long = 7) = DetailState(bookId, editor, { _, _ -> }, nav, toasts, backgroundScope)

  @Test fun `read and read again open this book, the second from the start`() = runTest {
    val detail = detail()
    detail.read(restart = false); detail.read(restart = true)
    assertEquals(listOf(ReaderRequest(7), ReaderRequest(7, restart = true)), nav.visits)
  }

  @Test fun `author, series and tag links open the library narrowed to them`() = runTest {
    val detail = detail()
    val author = Scope(ScopeKind.Author, "Jane Austen")
    detail.showScope(author); detail.back(); detail.openBook(8)
    assertEquals(listOf(author, Visit.Library, Visit.Detail(8)), nav.visits)
  }

  @Test fun `edits reach the store for this book`() = runTest {
    val detail = detail()
    detail.setFinished(true); detail.setRating(4); detail.addTag("Gothic"); detail.removeTag("Old")
    runCurrent()
    assertEquals(listOf("finished 7 true", "rating 7 4", "add 7 Gothic", "remove 7 Old"), editor.edits)
    assertEquals(listOf("Marked as finished"), toasts.shown)
  }

  @Test fun `the edit sheet opens and closes`() = runTest {
    val detail = detail()
    assertFalse(detail.editOpen.value)
    detail.openEdit(true); assertTrue(detail.editOpen.value)
    detail.openEdit(false); assertFalse(detail.editOpen.value)
  }
}
