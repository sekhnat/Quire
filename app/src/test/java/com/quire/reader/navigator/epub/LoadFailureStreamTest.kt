package com.quire.reader.navigator.epub

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadFailureStreamTest {
  /** What Readium's zip container does once the book holding it is closed. */
  private class ClosedZip : InputStream() {
    override fun read(): Int = throw IllegalStateException("zip file closed")
    override fun read(b: ByteArray, off: Int, len: Int): Int = throw IllegalStateException("zip file closed")
    override fun available(): Int = throw IllegalStateException("zip file closed")
  }

  @Test fun `a resource that reads normally is passed through`() {
    val bytes = "chapter".toByteArray()
    assertArrayEquals(bytes, LoadFailureStream(ByteArrayInputStream(bytes)).readBytes())
  }

  @Test fun `a closed book fails the load with an IOException instead of crashing`() {
    val stream = LoadFailureStream(ClosedZip())
    listOf<() -> Unit>({ stream.read() }, { stream.read(ByteArray(8), 0, 8) }, { stream.available() }).forEach { call ->
      val failure = runCatching(call).exceptionOrNull()
      assertTrue("$failure", failure is IOException)
      assertTrue("${failure?.cause}", failure?.cause is IllegalStateException)
    }
  }

  @Test fun `an IOException is passed on as it is`() {
    val original = IOException("disk")
    val stream = LoadFailureStream(object : InputStream() { override fun read(): Int = throw original })
    assertSame(original, runCatching { stream.read() }.exceptionOrNull())
    assertEquals(0, LoadFailureStream(ByteArrayInputStream(ByteArray(0))).available())
  }
}
