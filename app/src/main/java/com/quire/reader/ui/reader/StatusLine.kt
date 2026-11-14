package com.quire.reader.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.quire.reader.data.StatusItem
import com.quire.reader.data.StatusSlot
import com.quire.reader.data.StatusSlots
import com.quire.reader.data.WarmLight
import com.quire.reader.ui.Ic
import com.quire.reader.ui.Ph
import com.quire.reader.ui.QText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/**
 * One line of the status line: three slots, left, centre and right. A slot alone on its line may use the whole width
 * (the book title centred above the page, as it always was); otherwise each keeps to its share.
 */
@Composable
internal fun StatusLineRow(slots: StatusSlots, facts: StatusFacts, color: Color, modifier: Modifier = Modifier) {
  val texts = StatusSlot.entries.associateWith { StatusLineText.of(slots[it], facts) }
  if (texts.values.all { it == null }) return
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val sides = texts[StatusSlot.Left] != null || texts[StatusSlot.Right] != null
    val centre = texts[StatusSlot.Centre] != null
    val sideMax = if (centre) maxWidth * 0.32f else maxWidth * 0.49f
    val centreMax = if (sides) maxWidth * 0.34f else maxWidth
    texts[StatusSlot.Left]?.let { StatusText(it, slots.left, facts, color, TextAlign.Start, Modifier.align(Alignment.CenterStart).widthIn(max = sideMax)) }
    texts[StatusSlot.Centre]?.let { StatusText(it, slots.centre, facts, color, TextAlign.Center, Modifier.align(Alignment.Center).widthIn(max = centreMax)) }
    texts[StatusSlot.Right]?.let { StatusText(it, slots.right, facts, color, TextAlign.End, Modifier.align(Alignment.CenterEnd).widthIn(max = sideMax)) }
  }
}

@Composable
private fun StatusText(text: String, item: StatusItem, facts: StatusFacts, color: Color, align: TextAlign, modifier: Modifier) {
  val title = item == StatusItem.BookTitle || item == StatusItem.ChapterTitle
  if (item == StatusItem.Battery && facts.battery?.charging == true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      Ph(Ic.Bolt, 10.dp, color)
      QText(text, 11f, color = color, tabular = true, maxLines = 1)
    }
  } else {
    QText(text, 11f, modifier, color = color, ls = if (title) 0.04f else 0f, maxLines = 1, align = align, tabular = !title)
  }
}

/** Whether any slot of the shown lines uses [item], so its source (clock, battery) runs only when needed. */
internal fun com.quire.reader.data.StatusLine.uses(item: StatusItem): Boolean =
  (showHeader && StatusSlot.entries.any { header[it] == item }) || (showFooter && StatusSlot.entries.any { footer[it] == item })

/**
 * The time in milliseconds, updated at each minute boundary while the reader is resumed (the clock, and the warm light's
 * schedule). Nothing runs while the reader is in the background.
 */
@Composable
internal fun rememberMinuteTick(): State<Long> {
  val tick = remember { mutableLongStateOf(System.currentTimeMillis()) }
  LifecycleResumeEffect(Unit) {
    val job = kotlinx.coroutines.MainScope().launch {
      while (true) {
        val now = System.currentTimeMillis()
        tick.longValue = now
        delay(60_000 - now % 60_000 + 50)
      }
    }
    onPauseOrDispose { job.cancel() }
  }
  return tick
}

/** The time at [epochMs], 12 or 24 hours as the phone is set. */
@Composable
internal fun clockText(epochMs: Long): String {
  val context = LocalContext.current
  return remember(epochMs / 60_000) { DateFormat.getTimeFormat(context).format(Date(epochMs)) }
}

/** The battery, followed while the reader is resumed; null on devices without one. No permission is needed. */
@Composable
internal fun rememberBattery(enabled: Boolean): State<BatteryState?> {
  val context = LocalContext.current
  val state = remember { mutableStateOf<BatteryState?>(null) }
  LifecycleResumeEffect(enabled) {
    if (!enabled) return@LifecycleResumeEffect onPauseOrDispose { }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) { state.value = batteryOf(intent) }
    }
    // The battery broadcast is sticky: registering returns the current state at once.
    ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
      ?.let { state.value = batteryOf(it) }
    onPauseOrDispose { context.unregisterReceiver(receiver) }
  }
  return state
}

private fun batteryOf(intent: Intent): BatteryState? {
  if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return null
  val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
  val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
  if (level < 0 || scale <= 0) return null
  val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
  val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
  return BatteryState(level * 100 / scale, charging)
}

/** The amber of the warm light. */
private val WARM = Color(0xFFFF9329)

/**
 * The warm tint over the whole reader. Multiply darkens blue and green in proportion and leaves dark themes dark,
 * where a plain wash would lift the black; it never takes touches.
 */
@Composable
internal fun WarmTint(light: WarmLight, modifier: Modifier = Modifier) {
  val alpha = light.alpha
  Box(modifier.fillMaxSize().drawBehind { drawRect(WARM.copy(alpha = alpha * 2.2f), blendMode = BlendMode.Multiply) })
}
