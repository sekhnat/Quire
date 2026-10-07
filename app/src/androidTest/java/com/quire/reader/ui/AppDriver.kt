package com.quire.reader.ui

import com.quire.reader.ui.reader.ReaderLoad
import com.quire.reader.ui.reader.ReaderState

/** The open reader's state holder; fails when the reader isn't showing. */
val QuireViewModel.reader: ReaderState
  get() = (destination.value as? Destination.Reader)?.state ?: error("the reader is not open")

/** What the reader shows: [ReaderLoad.Idle] when it isn't open. */
val QuireViewModel.readerLoad: ReaderLoad
  get() = (destination.value as? Destination.Reader)?.state?.load?.value ?: ReaderLoad.Idle

/** Opens a book in the reader, as the library's and a book page's buttons do. */
fun QuireViewModel.read(bookId: Long, restart: Boolean = false) = openReader(ReaderRequest(bookId, restart = restart))

/** Leaves the reader for the library, as its back button does. */
fun QuireViewModel.closeReader() = openLibrary()
