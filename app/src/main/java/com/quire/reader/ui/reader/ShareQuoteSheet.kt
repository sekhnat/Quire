package com.quire.reader.ui.reader

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.quire.reader.reader.resolveFont
import com.quire.reader.reader.previewFamily
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts
import com.quire.reader.theme.ReaderTheme
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.Ic
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.SheetHost
import com.quire.reader.ui.Toggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** A passage waiting to be shared, with what the card needs to set it. */
data class QuoteDraft(
  val text: String,
  val title: String,
  val author: String,
  /** The reader's theme when Share was tapped; the card's first style. */
  val theme: ReaderTheme,
  /** The reading font (an index into the reader's fonts), or null for the book's own typography: Literata then. */
  val font: Int?,
)

/** The card styles: the reader's themes, without Dusk (which reads as Night on a card). */
private val CardStyles = listOf(ReaderTheme.Paper, ReaderTheme.Sepia, ReaderTheme.Night, ReaderTheme.Black)

/** The card is designed 411 dp wide and always drawn 1,080 px wide, the same on every phone. */
private const val CARD_DP = 411f
private val CardDensity = Density(QuoteCardSpec.WIDTH_PX / CARD_DP, fontScale = 1f)
private const val PAD = 36f
private const val FOOTER = 92f
private const val LINE_HEIGHT = 1.42f

/**
 * Share quote: a live preview of the card, its style, and Share image / Share text / Save to Pictures. The card is
 * composed at its real pixel size in a graphics layer that the preview shows scaled down; the PNG is that layer.
 */
@Composable
internal fun ShareQuoteSheet(draft: QuoteDraft?, onDismiss: () -> Unit, onShareText: (String) -> Unit, toast: (String) -> Unit) {
  val maxH = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
  SheetHost(draft != null, onDismiss, Modifier.heightIn(max = maxH), backdrop = 0.5f) {
    draft ?: return@SheetHost
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember(draft) { mutableStateOf(if (draft.theme == ReaderTheme.Dusk) ReaderTheme.Night else draft.theme) }
    var story by remember(draft) { mutableStateOf(false) }
    var wordmark by remember(draft) { mutableStateOf(true) }
    var busy by remember(draft) { mutableStateOf(false) }
    val family = draft.font?.let { resolveFont(it) }?.previewFamily() ?: QuireFonts.Literata
    val plain = shareQuote(draft.text, draft.title, draft.author)

    val measurer = rememberTextMeasurer()
    val fit = remember(draft, story, family) {
      val textWidth = with(CardDensity) { (CARD_DP - 2 * PAD).dp.roundToPx() }
      val maxTextHeight = with(CardDensity) {
        val cardHeight = (if (story) QuoteCardSpec.STORY_HEIGHT_PX else QuoteCardSpec.MAX_HEIGHT_PX).toFloat()
        cardHeight - (2 * PAD + FOOTER).dp.toPx()
      }
      fitQuote("“${draft.text.trim()}”", QuoteCardSpec.SIZES) { text, size ->
        val result = measurer.measure(text, quoteStyle(family, size), constraints = Constraints(maxWidth = textWidth), density = CardDensity)
        result.size.height <= maxTextHeight
      }
    }
    val layer = rememberGraphicsLayer()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      QText("Share quote", 17f, weight = 500)

      // The card at its real size, scaled to the sheet's width.
      BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val previewWidth = if (story) maxWidth * 0.62f else maxWidth
        val scale = with(LocalDensity.current) { previewWidth.toPx() } / QuoteCardSpec.WIDTH_PX
        Box(
          Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Nq.neutral800, RoundedCornerShape(10.dp)).layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
              placeable.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
            }
          },
        ) {
          QuoteCard(fit, draft, family, style, story, wordmark, layer)
        }
      }
      if (fit.shortened) QText("Shortened to fit", 11.5f, color = Nq.neutral500)

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardStyles.forEach { t ->
          val on = style == t
          val shape = RoundedCornerShape(8.dp)
          Column(Modifier.weight(1f).clickable { style = t }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(34.dp).clip(shape).background(t.bg).border(if (on) 2.dp else 1.dp, if (on) Nq.accent else Nq.neutral800, shape), contentAlignment = Alignment.Center) {
              QText("Aa", 14f, color = t.fg, family = family)
            }
            QText(t.label, 11f, color = if (on) Nq.accent else Nq.neutral400)
          }
        }
      }
      SwitchRow("Story size (9:16)", story) { story = !story }
      SwitchRow("Quire mark", wordmark) { wordmark = !wordmark }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QButton("Share text", { onShareText(plain); onDismiss() }, Modifier.weight(1f), icon = Ic.Copy, size = 12.5f)
        QButton("Share image", {
          if (busy) return@QButton
          busy = true
          scope.launch {
            val shared = runCatching { QuoteImages.share(context, layer.toImageBitmap().asAndroidBitmap(), plain) }
            busy = false
            if (shared.isSuccess) onDismiss() else toast("Couldn't make the image")
          }
        }, Modifier.weight(1f), BtnKind.Primary, icon = Ic.ShareNetwork, size = 12.5f)
      }
      QButton("Save to Pictures", {
        if (busy) return@QButton
        busy = true
        scope.launch {
          val saved = runCatching { QuoteImages.saveToPictures(context, layer.toImageBitmap().asAndroidBitmap()) }
          busy = false
          toast(if (saved.isSuccess) "Saved to Pictures/Quire" else "Couldn't save the image")
        }
      }, Modifier.fillMaxWidth(), icon = Ic.FileDown, size = 12.5f)
    }
  }
}

private fun quoteStyle(family: FontFamily, size: Int) =
  TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = (size * LINE_HEIGHT).sp, fontWeight = FontWeight.Normal)

/** The card itself, composed at 1,080 px wide in its own density and recorded into [layer]. */
@Composable
private fun QuoteCard(fit: QuoteFit, draft: QuoteDraft, family: FontFamily, style: ReaderTheme, story: Boolean, wordmark: Boolean, layer: GraphicsLayer) {
  CompositionLocalProvider(LocalDensity provides CardDensity) {
    val minH = QuoteCardSpec.MIN_HEIGHT_PX / CardDensity.density
    val maxH = (if (story) QuoteCardSpec.STORY_HEIGHT_PX else QuoteCardSpec.MAX_HEIGHT_PX) / CardDensity.density
    Column(
      Modifier
        .width(CARD_DP.dp)
        .then(if (story) Modifier.requiredSize(CARD_DP.dp, maxH.dp) else Modifier.heightIn(min = minH.dp, max = maxH.dp))
        .drawWithContent {
          layer.record { this@drawWithContent.drawContent() }
          drawLayer(layer)
        }
        .background(style.bg)
        .padding(PAD.dp),
      verticalArrangement = if (story) Arrangement.Center else Arrangement.SpaceBetween,
    ) {
      androidx.compose.material3.Text(fit.text, color = style.fg, style = quoteStyle(family, fit.size))
      if (story) Spacer(Modifier.height(28.dp))
      Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(40.dp).height(1.dp).background(style.muted))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            QText(draft.title, 13f, color = style.fg, family = family, ls = 0.1f, upper = true, maxLines = 2)
            if (draft.author.isNotBlank() && draft.author != "Unknown author") QText(draft.author, 12.5f, color = style.muted, family = family, maxLines = 1)
          }
          if (wordmark) QText("Quire", 12f, Modifier.padding(start = 12.dp), color = style.muted, weight = 500, ls = 0.04f)
        }
      }
    }
  }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    QText(label, 13f, Modifier.weight(1f), color = Nq.neutral300)
    Toggle(on, onClick)
  }
}

/** Writing and sending the card's PNG. */
internal object QuoteImages {

  /** Sends [bitmap] with [text] through the share sheet, from a fresh file in `cache/share` (older ones are deleted). */
  suspend fun share(context: Context, bitmap: Bitmap, text: String) {
    val file = withContext(Dispatchers.IO) {
      val dir = File(context.cacheDir, "share").apply { mkdirs() }
      dir.listFiles()?.forEach { it.delete() }
      File(dir, "quote-${System.currentTimeMillis()}.png").also { f -> f.outputStream().use { png(bitmap, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
      .setType("image/png")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .putExtra(Intent.EXTRA_TEXT, text)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  /** Saves [bitmap] to `Pictures/Quire` through MediaStore; no permission is needed. */
  suspend fun saveToPictures(context: Context, bitmap: Bitmap) = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "quote-${System.currentTimeMillis()}.png")
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
      put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Quire")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no media row")
    try {
      resolver.openOutputStream(uri)?.use { png(bitmap, it) } ?: error("cannot write $uri")
      resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      throw e
    }
  }

  private fun png(bitmap: Bitmap, out: OutputStream) {
    // A layer's bitmap may live on the GPU; encode a software copy.
    val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    check(soft.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encoding failed" }
  }
}
