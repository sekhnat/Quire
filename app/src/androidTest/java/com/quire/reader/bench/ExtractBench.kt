package com.quire.reader.bench

import com.quire.reader.data.index.BookExtractor
import com.quire.reader.data.index.SourceElement
import com.quire.reader.reader.PublicationLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Step 1: extracts every fixture EPUB once into [BenchCache] (`-e root` picks the folder, default the Calibre library). */
class ExtractBench : BenchStep() {
  @Test fun extract() = runBlocking {
    requireBench()
    val root = File(arg("root", "/sdcard/Calibre Library"))
    val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".epub", true) }.map { it.absolutePath }.sorted().toList()
    val done = cache.books().filter { cache.file(it.idx).isFile }.associateBy { it.path }
    val books = ArrayList<BenchBook>(done.values)
    val lock = Mutex()
    val next = AtomicInteger(0)
    val started = System.currentTimeMillis()
    val todo = files.withIndex().filter { it.value !in done }
    log("extract: ${files.size} files, ${todo.size} to do")
    (0 until 3).map {
      async(Dispatchers.Default) {
        while (true) {
          val (idx, path) = todo.getOrNull(next.getAndIncrement()) ?: break
          val file = File(path)
          val elements = ArrayList<SourceElement>()
          var ok = false
          var unreadable = 0
          var title = file.nameWithoutExtension
          PublicationLoader(ctx).open(file).onSuccess { publication ->
            try {
              title = publication.metadata.title ?: title
              val extractor = BookExtractor(publication)
              while (true) elements += extractor.next() ?: break
              unreadable = extractor.tallies.count { it.readFailed }
              ok = true
            } catch (e: Exception) {
              log("extract failed: $path: $e")
            } finally {
              publication.close()
            }
          }
          cache.write(idx, elements)
          lock.withLock {
            books += BenchBook(idx, path, file.lastModified(), file.length(), title, ok, unreadable, elements.size)
            if (books.size % 50 == 0) { cache.writeBooks(books); log("extract: ${books.size}/${files.size} after ${(System.currentTimeMillis() - started) / 1000} s") }
          }
        }
      }
    }.awaitAll()
    cache.writeBooks(books)
    report("extract.tsv", "books=${books.size}\tok=${books.count { it.ok }}\telements=${books.sumOf { it.elements }}\tseconds=${(System.currentTimeMillis() - started) / 1000}")
  }
}
