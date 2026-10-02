package com.quire.reader.reader

import android.content.Context
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** Opens EPUB files into Readium [Publication]s. One instance is shared by the scanner and the reader. */
class PublicationLoader(context: Context) {
  private val app = context.applicationContext
  private val httpClient = DefaultHttpClient()
  private val assetRetriever = AssetRetriever(app.contentResolver, httpClient)
  private val opener = PublicationOpener(
    publicationParser = DefaultPublicationParser(app, httpClient, assetRetriever, pdfFactory = null),
  )

  /** Returns the opened publication, or the reason it could not be read (corrupt, DRM, not an EPUB…). */
  suspend fun open(file: File): kotlin.Result<Publication> {
    val asset = assetRetriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { return kotlin.Result.failure(IllegalStateException(it.message)) }
    val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
      asset.close()
      return kotlin.Result.failure(IllegalStateException(it.message))
    }
    return kotlin.Result.success(publication)
  }
}
