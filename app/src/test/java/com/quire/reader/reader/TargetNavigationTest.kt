package com.quire.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetNavigationTest {
  @Test fun `arriving needs no explanation`() {
    assertNull(TargetOutcome.Arrived.message())
  }

  @Test fun `an unresolved passage says the exact text was unavailable`() {
    val message = TargetOutcome.Unresolved.message()!!
    assertEquals(true, message.contains("Exact passage unavailable"))
  }

  @Test fun `having no navigator is told apart from an unresolved passage`() {
    assertNotEquals(TargetOutcome.Unresolved.message(), TargetOutcome.NoNavigator.message())
    assertEquals(true, TargetOutcome.NoNavigator.message() != null)
  }

  @Test fun `an explicit target beats the saved position`() {
    assertEquals(OpeningPosition.Target, openingPosition(restart = false, hasTarget = true))
  }

  @Test fun `an explicit target also beats read again`() {
    assertEquals(OpeningPosition.Target, openingPosition(restart = true, hasTarget = true))
  }

  @Test fun `an ordinary opening restores the saved position`() {
    assertEquals(OpeningPosition.Saved, openingPosition(restart = false, hasTarget = false))
  }

  @Test fun `read again without a target starts at the beginning`() {
    assertEquals(OpeningPosition.Start, openingPosition(restart = true, hasTarget = false))
  }
}
