package com.quire.reader.ui

import com.quire.reader.data.scan.ScanPhase
import com.quire.reader.data.scan.ScanProgress
import java.text.NumberFormat
import java.util.Locale

/** The library's line about a running folder scan, and the fraction done once the total is known (null while walking). */
data class ScanStatus(val line: String, val progress: Float?)

/** What to show while a scan runs, or null when none is running. */
fun scanStatus(progress: ScanProgress): ScanStatus? = when (progress.phase) {
  ScanPhase.Finding -> {
    val added = if (progress.processed > 0) " · ${fmtCount(progress.processed)} added" else ""
    ScanStatus("Looking for books · ${fmtCount(progress.found)} found$added", null)
  }
  ScanPhase.Reading -> ScanStatus("Adding books · ${fmtCount(progress.processed)} of ${fmtCount(progress.total)}", progress.fraction)
  ScanPhase.Idle, ScanPhase.Done -> null
}

private fun fmtCount(n: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(n)
