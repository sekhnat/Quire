package com.quire.reader.ui

import com.quire.reader.data.scan.ScanPhase
import com.quire.reader.data.scan.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanStatusTest {
  @Test fun `nothing is shown when no scan is running`() {
    assertNull(scanStatus(ScanProgress(ScanPhase.Idle)))
    assertNull(scanStatus(ScanProgress(ScanPhase.Done, found = 10, processed = 10, total = 10)))
  }

  @Test fun `while folders are walked it says how many books were found`() {
    val status = scanStatus(ScanProgress(ScanPhase.Finding, found = 412))!!
    assertEquals("Looking for books · 412 found", status.line)
    assertNull(status.progress)
  }

  @Test fun `books added during the walk are counted too`() {
    assertEquals("Looking for books · 1,412 found · 180 added", scanStatus(ScanProgress(ScanPhase.Finding, found = 1412, processed = 180, total = 300))!!.line)
  }

  @Test fun `after the walk it counts the books still being added`() {
    val status = scanStatus(ScanProgress(ScanPhase.Reading, found = 1390, processed = 180, total = 1383))!!
    assertEquals("Adding books · 180 of 1,383", status.line)
    assertEquals(180f / 1383, status.progress!!, 1e-6f)
  }
}
