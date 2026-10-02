package com.quire.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.quire.reader.data.Book
import java.io.File
import com.quire.reader.theme.Nq
import com.quire.reader.theme.QuireFonts

/** The extracted cover thumbnail, cropped to fill the 2:3 cover box. */
@Composable
private fun CoverImage(path: String) {
  AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/**
 * The painted cover ground every book gets until a real cover image is extracted:
 * a gradient, a hairline inner edge, and optionally a soft drop shadow.
 */
@Composable
fun CoverBox(
  book: Book,
  modifier: Modifier = Modifier,
  radius: Dp = 4.dp,
  elevation: Dp = 0.dp,
  padding: PaddingValues = PaddingValues(0.dp),
  overlay: @Composable BoxScope.() -> Unit = {},
  content: @Composable BoxScope.() -> Unit = {},
) {
  val shape = RoundedCornerShape(radius)
  Box(
    modifier
      .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = Color.Black.copy(.35f), spotColor = Color.Black.copy(.35f)) else Modifier)
      .clip(shape)
      .background(book.ground.brush())
      .border(1.dp, Color.White.copy(alpha = 0.06f), shape),
  ) {
    if (book.coverPath != null) CoverImage(book.coverPath) else Box(Modifier.fillMaxSize().padding(padding), content = content)
    overlay()
  }
}

@Composable
private fun CoverTitle(text: String, size: Float) =
  QText(text, size, color = Nq.neutral100, family = QuireFonts.Literata, lh = 1.15f, balance = true)

@Composable
private fun AccentRule(width: Dp) = Box(Modifier.width(width).height(1.dp).background(Nq.accent))

/** Library grid cover: title, rule, author surname, progress line, and a dot for new books. */
@Composable
fun GridCover(book: Book, modifier: Modifier = Modifier) {
  CoverBox(
    book, modifier.aspectRatio(2f / 3f), elevation = 6.dp, padding = PaddingValues(horizontal = 9.dp, vertical = 10.dp),
    overlay = { CoverProgress(book); if (book.isNew) NewDot() },
  ) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
      CoverTitle(book.title, 12.5f)
      Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AccentRule(16.dp)
        QText(book.authorLast, 8.5f, color = Nq.neutral400, ls = 0.08f, upper = true, maxLines = 1)
      }
    }
  }
}

@Composable
private fun BoxScope.CoverProgress(book: Book) {
  if (book.pct > 0) {
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(book.pct / 100f).height(2.dp).background(Nq.accent))
  }
}

@Composable
private fun BoxScope.NewDot() {
  Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(6.dp).shadow(4.dp, CircleShape, spotColor = Nq.accent, ambientColor = Nq.accent).clip(CircleShape).background(Nq.accent))
}

/** Shelf cover: title, rule with series number, progress line. */
@Composable
fun ShelfCover(book: Book, modifier: Modifier = Modifier) {
  CoverBox(
    book, modifier.aspectRatio(2f / 3f), elevation = 6.dp, padding = PaddingValues(horizontal = 9.dp, vertical = 10.dp),
    overlay = { CoverProgress(book) },
  ) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
      CoverTitle(book.title, 12f)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        AccentRule(16.dp)
        if (book.seriesNoLabel != null) QText("#${book.seriesNoLabel}", 10f, color = Nq.accent200)
      }
    }
  }
}

@Composable
fun ListCover(book: Book) {
  CoverBox(book, Modifier.size(width = 34.dp, height = 51.dp), radius = 3.dp, padding = PaddingValues(5.dp)) {
    Box(Modifier.align(Alignment.BottomStart)) { AccentRule(10.dp) }
  }
}

@Composable
fun HeroCover(book: Book) {
  CoverBox(book, Modifier.size(width = 64.dp, height = 96.dp), padding = PaddingValues(horizontal = 7.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
      CoverTitle(book.title, 10f)
      AccentRule(14.dp)
    }
  }
}

@Composable
fun DetailCover(book: Book) {
  CoverBox(book, Modifier.size(width = 118.dp, height = 177.dp), radius = 5.dp, elevation = 14.dp, padding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
      CoverTitle(book.title, 16f)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AccentRule(20.dp)
        QText(book.author, 9f, color = Nq.neutral400, ls = 0.1f, upper = true)
      }
    }
  }
}

/** A numbered cover in a series strip; dimmed once finished. */
@Composable
fun SeriesCover(book: Book, dim: Boolean) {
  CoverBox(
    book, Modifier.width(52.dp).aspectRatio(2f / 3f).alpha(if (dim) 0.55f else 1f), radius = 3.dp, padding = PaddingValues(horizontal = 5.dp, vertical = 4.dp),
    overlay = { CoverProgress(book) },
  ) {
    book.seriesNoLabel?.let { QText("#$it", 10f, Modifier.align(Alignment.BottomEnd), color = Nq.accent200) }
  }
}

@Composable
fun MissingCover(num: Int) {
  val shape = RoundedCornerShape(3.dp)
  Box(Modifier.width(52.dp).aspectRatio(2f / 3f).border(1.dp, Nq.neutral700, shape).padding(horizontal = 5.dp, vertical = 4.dp)) {
    QText("#$num", 10f, Modifier.align(Alignment.BottomEnd), color = Nq.neutral600)
  }
}

@Composable
fun MoreCover(book: Book, modifier: Modifier = Modifier) {
  CoverBox(book, modifier.aspectRatio(2f / 3f), padding = PaddingValues(8.dp)) {
    CoverTitle(book.title, 11f)
  }
}
