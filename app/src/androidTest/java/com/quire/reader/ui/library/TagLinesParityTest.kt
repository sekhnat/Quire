package com.quire.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.quire.reader.ui.Tag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

/**
 * The lazy tag cloud measures chips with a Paint off the main thread and packs them itself. It must land on exactly the
 * lines the FlowRow of real `Tag` chips it replaced would, at any font scale, or a line's last chip gets squeezed.
 */
class TagLinesParityTest {
  @get:Rule val rule = createComposeRule()

  private val tags: List<Pair<String, Int>> = run {
    val words = "alpha bramble cinder dusk ember fable gossamer harbor ivory juniper kestrel lantern meadow nocturne orchard pilgrim quarry raven saffron thistle umber vesper willow yarrow zephyr gothic noir satire WWII Sci-fi".split(" ")
    val r = Random(7)
    List(300) { i -> List(1 + r.nextInt(3)) { words[r.nextInt(words.size)] }.joinToString(" ") to 1 + r.nextInt(if (i % 10 == 0) 400 else 30) }
  }

  @OptIn(ExperimentalLayoutApi::class)
  @Test fun chipWidthsAndLinesMatchFlowRow() {
    val available = 1000
    var fontScale by mutableFloatStateOf(1f)
    lateinit var metrics: TagChipMetrics
    val widths = IntArray(tags.size)
    val tops = IntArray(tags.size)
    rule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
        metrics = rememberTagChipMetrics()
        FlowRow(
          Modifier.layout { m, _ -> val p = m.measure(Constraints(maxWidth = available)); layout(p.width, p.height) { p.place(0, 0) } },
          horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          tags.forEachIndexed { i, (name, n) ->
            val at = Modifier.onGloballyPositioned { widths[i] = it.size.width; tops[i] = it.positionInParent().y.roundToInt() }
            Tag(name, {}, at, size = 13f, count = n.toString(), hPad = 12.dp, vPad = 7.dp)
          }
        }
      }
    }
    for (scale in listOf(1f, 1.15f, 1.3f, 2f)) {
      rule.runOnIdle { fontScale = scale }
      rule.waitForIdle()
      val m = rule.runOnIdle { metrics }
      val computed = tagChipWidths(tags, m)
      val off = tags.indices.filter { minOf(computed[it], available) != widths[it] }.map { "${tags[it].first}: ${computed[it]} vs ${widths[it]}" }
      assertEquals("chip widths that differ at font scale $scale", emptyList<String>(), off)
      val flowRowLines = tags.indices.groupBy { tops[it] }.values.map { it.first()..it.last() }
      assertEquals("lines at font scale $scale", flowRowLines, packLines(computed, available, m.gapPx))
    }
  }
}
