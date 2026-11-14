package com.quire.reader.ui.detail

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.quire.reader.data.CoverCrop
import com.quire.reader.theme.Nq
import com.quire.reader.ui.BtnKind
import com.quire.reader.ui.QButton
import com.quire.reader.ui.QText
import com.quire.reader.ui.SheetHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes [bytes] for the screen, scaled down by powers of two to at most [maxSide] pixels; null when it is no image. */
private fun decodeForScreen(bytes: ByteArray, maxSide: Int): Pair<ImageBitmap, Int>? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
  val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
  return bmp.asImageBitmap() to sample
}

/**
 * Framing a cover: the image under a fixed 2:3 frame, moved with a finger and zoomed with two. What lies inside the frame
 * becomes the cover. The crop is worked out on the image's own pixels ([CoverCrop.sourceRect]), whatever size it is shown.
 */
@Composable
internal fun CoverCropScreen(bytes: ByteArray, onCancel: () -> Unit, onSave: (com.quire.reader.data.PixelRect) -> Unit) {
  BackHandler(onBack = onCancel)
  val decoded by produceState<Pair<ImageBitmap, Int>?>(null, bytes) { value = withContext(Dispatchers.Default) { decodeForScreen(bytes, 2048) } }
  Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
    QText("Move and zoom the picture to frame the cover", 13f, Modifier.padding(20.dp).fillMaxWidth(), color = Nq.neutral300)
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      val density = LocalDensity.current
      // The frame: as tall as fits, 2:3, with room around it.
      val frameH = minOf(maxHeight * 0.9f, maxWidth * 0.8f * 1.5f)
      val frameW = frameH / 1.5f
      val frameWpx = with(density) { frameW.toPx() }
      val frameHpx = with(density) { frameH.toPx() }
      val pair = decoded
      var scale by remember(bytes) { mutableFloatStateOf(1f) }
      var offset by remember(bytes) { mutableStateOf(Offset.Zero) }
      if (pair != null) {
        val (image, sample) = pair
        val fullW = image.width * sample
        val fullH = image.height * sample
        val k = CoverCrop.pixelsPerImagePixel(fullW, fullH, frameWpx, frameHpx, scale)
        Box(
          Modifier.fillMaxSize().pointerInput(bytes, frameWpx) {
            detectTransformGestures { _, pan, zoom, _ ->
              scale = (scale * zoom).coerceIn(1f, CoverCrop.MAX_SCALE)
              val (x, y) = CoverCrop.clampOffset(offset.x + pan.x, offset.y + pan.y, fullW, fullH, frameWpx, frameHpx, scale)
              offset = Offset(x, y)
            }
          },
          contentAlignment = Alignment.Center,
        ) {
          // The picture at its framed size, moved by the offset; laid out unconstrained so it can overflow the screen.
          val shownW = with(density) { (fullW * k).toDp() }
          val shownH = with(density) { (fullH * k).toDp() }
          Image(image, null, Modifier.requiredSize(shownW, shownH).graphicsLayer { translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.FillBounds)
          // Everything outside the frame is dimmed; the frame itself is outlined.
          Canvas(Modifier.fillMaxSize()) {
            val frame = Rect(Offset((size.width - frameWpx) / 2f, (size.height - frameHpx) / 2f), Size(frameWpx, frameHpx))
            val outside = Path().apply {
              fillType = PathFillType.EvenOdd
              addRect(Rect(Offset.Zero, size))
              addRect(frame)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.6f))
            drawRect(Color.White.copy(alpha = 0.9f), frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
          }
        }
      } else {
        QText("Opening the picture…", 13f, color = Nq.neutral400)
      }
      val save: () -> Unit = {
        decoded?.let { (image, sample) ->
          onSave(CoverCrop.sourceRect(image.width * sample, image.height * sample, frameWpx, frameHpx, scale, offset.x, offset.y))
        }
      }
      Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QButton("Cancel", onCancel, Modifier.weight(1f), size = 13f, height = 44.dp)
        QButton("Use as cover", save, Modifier.weight(1f), BtnKind.Primary, size = 13f, height = 44.dp, enabled = decoded != null)
      }
    }
  }
}

/** The book's own pictures in a grid, to choose a cover from. */
@Composable
internal fun BookImagesSheet(images: List<ByteArray>?, loading: Boolean, onPick: (ByteArray) -> Unit, onDismiss: () -> Unit) {
  val maxH = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
  SheetHost(images != null, onDismiss, Modifier.heightIn(max = maxH).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      QText("Pictures in this book", 17f, weight = 500)
      when {
        loading -> QText("Looking through the book…", 13f, color = Nq.neutral400)
        images.isNullOrEmpty() -> QText("This book has no pictures to use as a cover.", 13f, color = Nq.neutral400)
        else -> LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          itemsIndexed(images) { _, bytes ->
            val thumb by produceState<ImageBitmap?>(null, bytes) { value = withContext(Dispatchers.Default) { decodeForScreen(bytes, 360)?.first } }
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.aspectRatio(2f / 3f).clip(shape).background(Nq.surface).border(1.dp, Nq.neutral800, shape).clickable { onPick(bytes) }) {
              thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
          }
        }
      }
    }
  }
}
