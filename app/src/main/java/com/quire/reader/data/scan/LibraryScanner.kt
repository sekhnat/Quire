package com.quire.reader.data.scan

import android.util.Log
import com.quire.reader.data.SettingsStore
import com.quire.reader.data.db.BookEntity
import com.quire.reader.data.db.FolderEntity
import com.quire.reader.data.db.QuireDatabase
import com.quire.reader.reader.PublicationLoader
import org.readium.r2.shared.publication.services.cover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.max

enum class ScanPhase { Idle, Finding, Reading, Done }

data class ScanProgress(
  val phase: ScanPhase = ScanPhase.Idle,
  /** EPUB files seen so far (grows during [ScanPhase.Finding]). */
  val found: Int = 0,
  /** Files read so far out of [total] that needed reading. */
  val processed: Int = 0,
  val total: Int = 0,
  val currentFile: String = "",
) {
  val fraction: Float get() = when (phase) { ScanPhase.Done -> 1f; ScanPhase.Reading -> if (total == 0) 1f else processed.toFloat() / total; else -> 0f }
}

data class ScanResult(val added: Int, val updated: Int, val removed: Int, val unreadable: Int)

/**
 * Keeps the database in step with the watched folders. Only new or changed files are read, in parallel,
 * and a failure on one file never stops the scan.
 */
class LibraryScanner(
  private val db: QuireDatabase,
  private val loader: PublicationLoader,
  private val covers: CoverStore,
  private val settings: SettingsStore,
) {
  private val _progress = MutableStateFlow(ScanProgress())
  val progress: StateFlow<ScanProgress> = _progress.asStateFlow()
  private val mutex = Mutex()

  val isRunning: Boolean get() = _progress.value.phase == ScanPhase.Finding || _progress.value.phase == ScanPhase.Reading

  suspend fun scan(): ScanResult = mutex.withLock { withContext(Dispatchers.IO) { doScan() } }

  private suspend fun doScan(): ScanResult {
    val startedAt = System.currentTimeMillis()
    val useCalibre = settings.useCalibre.first()
    val folders = db.folders().watched()
    val folderById = folders.associateBy { it.id }
    val known = db.books().knownFiles().associateBy { it.path }
    _progress.value = ScanProgress(ScanPhase.Finding)

    val foundPaths = HashSet<String>()
    val reachable = mutableSetOf<Long>()
    val found = AtomicInteger(0)
    val queued = AtomicInteger(0)
    val done = AtomicInteger(0)
    val unreadable = AtomicInteger(0)
    val updated = AtomicInteger(0)
    val walking = AtomicBoolean(true)
    fun publish(currentFile: String) = synchronized(_progress) {
      _progress.value = ScanProgress(if (walking.get()) ScanPhase.Finding else ScanPhase.Reading, found.get(), done.get(), queued.get(), currentFile)
    }

    // Walk the folders, reading each new or changed file as soon as it is found.
    readWhileFinding<FoundFile>(
      PARALLELISM,
      walk = { emit ->
        for (folder in folders) {
          val root = File(folder.path)
          if (!root.isDirectory || !root.canRead()) { Log.w(TAG, "folder unreachable: ${folder.path}"); continue }
          reachable += folder.id
          walk(root) { f ->
            val file = FoundFile(f.absolutePath, folder.id, f.length(), f.lastModified())
            foundPaths += file.path
            val n = found.incrementAndGet()
            if (needsRead(file, known[file.path])) { queued.incrementAndGet(); emit(file) }
            if (n % 25 == 0) publish(relative(f, root))
          }
        }
        walking.set(false)
        publish("")
      },
      read = { file ->
        val existing = known[file.path]
        if (existing != null) updated.incrementAndGet()
        val ok = runCatching { readAndStore(file, folderById.getValue(file.folderId), existing?.id ?: 0L, existing?.addedAt, useCalibre) }
          .onFailure { Log.w(TAG, "failed to read ${file.path}", it) }
          .getOrDefault(false)
        if (!ok) unreadable.incrementAndGet()
        done.incrementAndGet()
        publish(File(file.path).name)
      },
    )

    // Only a finished walk shows which known books are gone.
    val removedIds = removedIds(foundPaths, known, reachable)
    val byId = known.values.associateBy { it.id }
    removedIds.forEach { covers.delete(byId[it]?.coverPath) }
    if (removedIds.isNotEmpty()) db.books().delete(removedIds)

    val now = System.currentTimeMillis()
    reachable.forEach { db.folders().markScanned(it, now) }
    val read = queued.get()
    _progress.value = ScanProgress(ScanPhase.Done, found.get(), read, read)
    val added = read - updated.get()
    Log.i(TAG, "scan done: ${found.get()} files, $read read, ${removedIds.size} removed, ${unreadable.get()} unreadable in ${System.currentTimeMillis() - startedAt} ms")
    return ScanResult(added = added, updated = updated.get(), removed = removedIds.size, unreadable = unreadable.get())
  }

  /** Returns false when the book was stored but could not be opened as an EPUB. */
  private suspend fun readAndStore(file: FoundFile, folder: FolderEntity, existingId: Long, existingAddedAt: Long?, useCalibre: Boolean): Boolean {
    val epub = File(file.path)
    val opfFile = File(epub.parentFile, "metadata.opf")
    var meta: OpfMetadata? = null
    var source = BookEntity.SOURCE_FILE
    var coverPath: String? = null
    var readable = true

    if (useCalibre && opfFile.isFile) {
      meta = runCatching { opfFile.inputStream().use(OpfParser::parse) }.getOrNull()
      if (meta != null) {
        source = BookEntity.SOURCE_CALIBRE
        val cover = File(epub.parentFile, "cover.jpg")
        if (cover.isFile) coverPath = covers.saveFromFile(cover, file.path)
      }
    }
    if (meta == null || (coverPath == null && source == BookEntity.SOURCE_CALIBRE)) {
      // No Calibre metadata (or no cover.jpg): ask the EPUB itself.
      val opened = loader.open(epub)
      opened.onSuccess { pub ->
        try {
          if (meta == null) meta = pub.toOpfMetadata()
          if (coverPath == null) pub.cover()?.let { bmp -> coverPath = covers.saveFromBitmap(bmp, file.path) }
        } finally { pub.close() }
      }.onFailure { readable = false }
    }

    val m = meta ?: OpfMetadata(
      title = epub.nameWithoutExtension.replace('_', ' ').trim().ifEmpty { "Untitled" },
      titleSort = null, authors = emptyList(), authorSort = null, series = null, seriesIndex = null, tags = emptyList(),
      rating = 0, description = null, year = null, language = null, addedAtMillis = null,
    )
    val author = m.authors.joinToString(" & ").ifEmpty { UNKNOWN_AUTHOR }
    val primary = m.authors.firstOrNull() ?: UNKNOWN_AUTHOR
    val entity = BookEntity(
      id = existingId,
      path = file.path,
      folderId = folder.id,
      sizeBytes = file.sizeBytes,
      mtime = file.mtime,
      title = m.title,
      sortTitle = m.titleSort?.lowercase() ?: sortKey(m.title),
      author = author,
      primaryAuthor = primary,
      authorSort = (m.authorSort ?: authorSortFrom(primary)).lowercase(),
      series = m.series,
      seriesIndex = m.seriesIndex,
      pubYear = m.year,
      language = m.language,
      description = m.description,
      calibreRating = m.rating,
      addedAt = existingAddedAt ?: (if (source == BookEntity.SOURCE_CALIBRE) m.addedAtMillis else null) ?: file.mtime,
      pageEstimate = estimatePages(epub),
      coverPath = coverPath,
      source = source,
      readable = readable,
    )
    db.books().save(entity, m.tags)
    return readable
  }

  private suspend fun walk(root: File, onFile: suspend (File) -> Unit) {
    val stack = ArrayDeque<File>().apply { add(root) }
    while (stack.isNotEmpty()) {
      val dir = stack.removeLast()
      for (f in dir.listFiles().orEmpty()) {
        when {
          f.name.startsWith(".") -> continue
          f.isDirectory -> if (!(dir == root && f.name == "Android")) stack.add(f)
          f.name.endsWith(".epub", ignoreCase = true) -> onFile(f)
        }
      }
    }
  }

  private fun relative(f: File, root: File) = f.path.removePrefix(root.parent.orEmpty()).trimStart('/')

  companion object {
    private const val TAG = "LibraryScanner"
    private const val PARALLELISM = 4
    const val UNKNOWN_AUTHOR = "Unknown author"

    /** Pages from the amount of text in the EPUB's HTML files (no decompression needed). */
    fun estimatePages(file: File): Int {
      val chars = runCatching {
        ZipFile(file).use { zip ->
          var total = 0L
          for (e in zip.entries()) {
            val n = e.name.lowercase()
            if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) total += max(0L, e.size)
          }
          total
        }
      }.getOrDefault(0L)
      return if (chars > 0) max(1, (chars / CHARS_PER_PAGE).toInt()) else max(1, (file.length() / 2000).toInt())
    }
    /** HTML bytes per Readium position; calibrated so a typical EPUB estimate lands near the real count. */
    private const val CHARS_PER_PAGE = 3000

    /** "The Hound of the Baskervilles" sorts under H. */
    fun sortKey(title: String): String {
      val t = title.trim().lowercase()
      for (article in listOf("the ", "a ", "an ")) if (t.startsWith(article) && t.length > article.length) return t.removePrefix(article)
      return t
    }

    /** "Arthur Conan Doyle" → "doyle, arthur conan". */
    fun authorSortFrom(name: String): String {
      if (name == UNKNOWN_AUTHOR) return "~"
      val parts = name.trim().split(Regex("\\s+"))
      return if (parts.size < 2) name.lowercase() else "${parts.last()}, ${parts.dropLast(1).joinToString(" ")}".lowercase()
    }
  }
}
