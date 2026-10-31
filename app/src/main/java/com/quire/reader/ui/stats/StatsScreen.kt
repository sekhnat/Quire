package com.quire.reader.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quire.reader.theme.Nq
import com.quire.reader.ui.Ic
import com.quire.reader.ui.IconBtn
import com.quire.reader.ui.Kicker
import com.quire.reader.ui.QText
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun StatsScreen(stats: StatsState) {
  val ui by stats.ui.collectAsStateWithLifecycle()
  BackHandler { stats.leave() }
  Column(Modifier.fillMaxSize().background(Nq.bg).statusBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconBtn(Ic.ArrowLeft, stats::leave)
      QText("Reading stats", 20f, Modifier.padding(start = 4.dp), weight = 500)
    }
    val summary = ui.summary
    when {
      summary == null -> Unit
      summary.isEmpty -> QText(
        "Read for a few minutes and your stats appear here.", 13f,
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp), color = Nq.neutral500, align = TextAlign.Center,
      )
      else -> Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Today", duration(summary.todayMs), Modifier.weight(1f))
            Tile("This week", duration(summary.weekMs), Modifier.weight(1f))
            Tile("This month", duration(summary.monthMs), Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Streak", days(summary.currentStreak), Modifier.weight(1f), "best ${days(summary.longestStreak)}")
            Tile("Finished this year", summary.finishedThisYear.toString(), Modifier.weight(1f))
            Tile("Pace", summary.pagesPerHour?.let { "$it/h" } ?: "—", Modifier.weight(1f), if (summary.pagesPerHour != null) "pages an hour" else "not enough yet")
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Kicker("Last 30 days")
          DayChart(summary.lastDays)
          val first = summary.lastDays.first().date
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QText(first.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), 11f, color = Nq.neutral500)
            QText("Today", 11f, color = Nq.neutral500)
          }
        }

        if (summary.topBooks.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Kicker("Most read")
          summary.topBooks.forEach { top ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
              QText(ui.titles[top.bookId] ?: "A book no longer in the library", 14f, Modifier.weight(1f), color = if (top.bookId in ui.titles) Nq.text else Nq.neutral500, maxLines = 1)
              QText(duration(top.ms), 13f, color = Nq.neutral400, tabular = true)
            }
          }
          QText("${duration(summary.totalMs)} in all", 11.5f, Modifier.padding(top = 4.dp), color = Nq.neutral500)
        }
      }
    }
  }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier, note: String? = null) {
  Column(
    modifier.clip(RoundedCornerShape(12.dp)).background(Nq.surface).padding(horizontal = 12.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    QText(label, 11f, color = Nq.neutral500, maxLines = 1)
    QText(value, 19f, weight = 500, maxLines = 1, tabular = true)
    QText(note ?: " ", 10.5f, color = Nq.neutral500, maxLines = 1)
  }
}

/** One bar a day, the tallest day filling the height; days without reading show a thin stub so the row reads as a row. */
@Composable
private fun DayChart(days: List<DayTime>) {
  val best = days.maxOf { it.ms }.coerceAtLeast(1L)
  val summary = "Reading time over the last ${days.size} days, ${duration(days.sumOf { it.ms })} in all, best day ${duration(best)}"
  Box(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = summary }) {
    Canvas(Modifier.fillMaxSize()) {
      val gap = 3.dp.toPx()
      val barWidth = (size.width - gap * (days.size - 1)) / days.size
      val stub = 2.dp.toPx()
      days.forEachIndexed { i, day ->
        val h = if (day.ms == 0L) stub else (size.height * day.ms / best).coerceAtLeast(stub)
        drawRoundRect(
          color = if (day.ms == 0L) Nq.neutral800 else Nq.accent,
          topLeft = Offset(i * (barWidth + gap), size.height - h),
          size = Size(barWidth, h),
          cornerRadius = CornerRadius(2.dp.toPx()),
        )
      }
    }
  }
}

/** "0m", "42m", "3h 05m". */
internal fun duration(ms: Long): String {
  val minutes = (ms / 60_000).toInt()
  return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${"%02d".format(minutes % 60)}m"
}

internal fun days(n: Int): String = if (n == 1) "1 day" else "$n days"
