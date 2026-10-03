package com.quire.reader.data.index

import org.jsoup.Jsoup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceOrderTest {
  private fun css(html: String, query: String) = Jsoup.parse(html).selectFirst(query)!!.cssSelector()

  @Test fun `an element sits where its text starts and an anchor where it opens`() {
    val html = "<body><p class=\"one\">One.</p><div id=\"wrap\"><a id=\"x\"></a><h2>Two</h2></div><p class=\"three\">Three.</p></body>"
    val order = ResourceOrder.parse(html)!!
    val one = order.element(css(html, ".one"))!!
    val anchor = order.anchor("x")!!
    val heading = order.element(css(html, "h2"))!!
    val three = order.element(css(html, ".three"))!!
    assertTrue(one < anchor)
    assertTrue(anchor < heading)
    assertTrue(heading < three)
    assertTrue("the wrapper's text starts after its empty anchor", order.element(css(html, "#wrap"))!! >= anchor)
  }

  @Test fun `anchors are found by id on any element and by name on links`() {
    val order = ResourceOrder.parse("<body><h2 id=\"a\">A</h2><p><a name=\"b\"></a>B</p><div name=\"c\">C</div></body>")!!
    assertNotNull(order.anchor("a"))
    assertNotNull(order.anchor("b"))
    assertNull("name only counts on links", order.anchor("c"))
    assertNull(order.anchor("missing"))
  }

  @Test fun `an element whose text starts before an anchor inside it is not under that anchor`() {
    val html = "<body><p class=\"p\">Lead <a id=\"in\"></a>tail</p></body>"
    val order = ResourceOrder.parse(html)!!
    assertTrue(order.element(css(html, ".p"))!! < order.anchor("in")!!)
  }

  @Test fun `elements without text and unknown selectors have no position`() {
    val html = "<body><div class=\"empty\"> <br/> </div><p>Text</p></body>"
    val order = ResourceOrder.parse(html)!!
    assertNull(order.element(css(html, ".empty")))
    assertNull(order.element("#nothing > p"))
  }

  @Test fun `a resource too large to parse again has no order`() {
    assertNull(ResourceOrder.parse("<body><p>" + "x".repeat(ResourceOrder.MAX_CHARS) + "</p></body>"))
  }
}
