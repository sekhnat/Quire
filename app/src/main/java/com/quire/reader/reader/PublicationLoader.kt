package com.quire.reader.reader

import android.content.Context
import com.quire.reader.data.mobi.ConvertedBooks
import com.quire.reader.data.scan.BookFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * Opens book files into Readium [Publication]s. One instance is shared by the scanner, the indexer and the reader. MOBI
 * and AZW3 books are opened through an EPUB copy made on first use (see [ConvertedBooks]).
 */
class PublicationLoader(context: Context) {
  private val app = context.applicationContext
  private val httpClient = DefaultHttpClient()
  private val assetRetriever = AssetRetriever(app.contentResolver, httpClient)
  private val opener = PublicationOpener(
    publicationParser = DefaultPublicationParser(app, httpClient, assetRetriever, pdfFactory = null),
  )

  private val converted = ConvertedBooks(File(app.cacheDir, "converted"))

  /** Returns the opened publication, or the reason it could not be read (corrupt, DRM, not a book…). */
  suspend fun open(file: File): kotlin.Result<Publication> {
    val epub = if (!BookFormats.isMobi(file)) file else {
      withContext(Dispatchers.IO) { runCatching { converted.epubFor(file) } }.getOrElse { return kotlin.Result.failure(IllegalStateException(it.message, it)) }
    }
    val asset = assetRetriever.retrieve(epub.toUrl(isDirectory = false)).getOrElse { return kotlin.Result.failure(IllegalStateException(it.message)) }
    val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
      asset.close()
      return kotlin.Result.failure(IllegalStateException(it.message))
    }
    return kotlin.Result.success(publication)
  }
}
