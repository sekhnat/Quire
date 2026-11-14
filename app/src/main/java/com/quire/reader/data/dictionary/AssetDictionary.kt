package com.quire.reader.data.dictionary

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.quire.qdict.Dictionary
import com.quire.qdict.LookupResult
import com.quire.qdict.QdictException
import com.quire.qdict.QdictReader
import com.quire.qdict.RandomAccessSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The bundled dictionary, read in place from the APK. The asset is stored uncompressed (`noCompress += "qdict"`), so its
 * file descriptor points into the APK at its own offset: reads go straight there, and nothing is ever copied out.
 */
class AssetDictionarySource(context: Context, path: String) : RandomAccessSource, Closeable {
  private val afd: AssetFileDescriptor = context.assets.openFd(path)
  private val stream = FileInputStream(afd.fileDescriptor)
  private val channel = stream.channel
  private val start = afd.startOffset
  override val size: Long = afd.length

  override fun read(offset: Long, length: Int): ByteArray {
    if (offset < 0 || length < 0 || offset + length > size) throw QdictException("read past the end")
    val buf = ByteBuffer.allocate(length)
    var at = start + offset
    // Positional reads: safe from several threads, and they never move the shared channel position.
    while (buf.hasRemaining()) {
      val n = channel.read(buf, at)
      if (n < 0) throw IOException("dictionary asset ended early")
      at += n
    }
    return buf.array()
  }

  override fun close() {
    runCatching { channel.close() }
    runCatching { stream.close() }
    runCatching { afd.close() }
  }
}

/** Looks words up for the reader's definition card. */
interface WordLookup : Closeable {
  suspend fun lookUp(text: String): LookupResult
}

/**
 * The bundled dictionary, opened at the first lookup (reading its small tables: well under a second) and closed with the
 * reader, so nothing stays in memory once the book is closed.
 */
class BundledWordLookup(private val context: Context) : WordLookup {
  private val mutex = Mutex()
  private var source: AssetDictionarySource? = null
  private var dictionary: Dictionary? = null

  override suspend fun lookUp(text: String): LookupResult = withContext(Dispatchers.IO) {
    val dict = mutex.withLock {
      dictionary ?: runCatching {
        val name = context.assets.list(DIR)?.firstOrNull { it.endsWith(".qdict") } ?: error("no dictionary in the app")
        val src = AssetDictionarySource(context, "$DIR/$name")
        source = src
        Dictionary(QdictReader(src))
      }.onFailure { Timber.w(it, "dictionary unavailable") }.getOrNull()?.also { dictionary = it }
    } ?: return@withContext LookupResult.NotFound(text)
    runCatching { dict.lookUp(text) }.onFailure { Timber.w(it, "lookup failed") }.getOrElse { LookupResult.NotFound(text) }
  }

  override fun close() {
    source?.close()
    source = null
    dictionary = null
  }

  companion object {
    const val DIR = "dictionary"
  }
}
