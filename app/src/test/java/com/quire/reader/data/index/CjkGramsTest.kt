package com.quire.reader.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkGramsTest {
  @Test fun `Han, kana and Hangul are CJK, Latin, digits and CJK punctuation are not`() {
    for (c in "日本語ひらがなカタカナ한국어ー々") assertTrue("$c", CjkGrams.isCjk(c.code))
    for (c in "abcÉ1、。「」 ") assertFalse("$c", CjkGrams.isCjk(c.code))
    assertTrue(CjkGrams.isCjk("𠀋".codePointAt(0))) // Extension B, a surrogate pair
  }

  @Test fun `each run gives its overlapping two-character grams and then its last character`() {
    assertEquals("東京 京都 都", CjkGrams.grams("東京都"))
    assertEquals("猫", CjkGrams.grams("猫"))
    assertEquals("東京 京 京都 都", CjkGrams.grams("東京、京都"))
    assertEquals("東京 京 大阪 阪", CjkGrams.grams("Tokyo 東京 and Osaka 大阪!"))
    assertNull(CjkGrams.grams("No CJK here at all."))
    assertEquals("𠀋𠀋 𠀋", CjkGrams.grams("𠀋𠀋")) // code points, never half a pair
    assertEquals("コー ーヒ ヒー ー", CjkGrams.grams("コーヒー"))
  }

  @Test fun `every character of a run starts at least one gram`() {
    val run = "吾輩は猫である"
    val grams = CjkGrams.grams(run)!!.split(' ')
    for (i in run.indices) assertTrue(grams.any { it.startsWith(run[i]) })
  }

  @Test fun `a query run is a phrase of its grams and a single character a prefix`() {
    assertEquals("\"猫\"*", CjkGrams.queryTerm("猫"))
    assertEquals("\"東京\"", CjkGrams.queryTerm("東京"))
    assertEquals("\"東京 京都\"", CjkGrams.queryTerm("東京都"))
    assertEquals("\"𠀋𠀋\"", CjkGrams.queryTerm("𠀋𠀋"))
  }

  /** The phrase of a query run's grams occurs in an indexed run's gram list exactly where the query occurs in the run. */
  @Test fun `a gram phrase matches exactly where its run occurs as a substring`() {
    val texts = listOf("東京都に住む東京の人", "京都と東京", "東京", "東", "都都都")
    val queries = listOf("東京", "京都", "東京都", "都都", "東京の人", "人", "都", "京")
    for (text in texts) for (query in queries) {
      val tokens = CjkGrams.grams(text)!!.split(' ')
      val term = CjkGrams.queryTerm(query)
      val found = if (term.endsWith("*")) {
        val c = term.removePrefix("\"").removeSuffix("\"*")
        tokens.any { it.startsWith(c) }
      } else {
        val phrase = term.removeSurrounding("\"").split(' ')
        (0..tokens.size - phrase.size).any { tokens.subList(it, it + phrase.size) == phrase }
      }
      assertEquals("'$query' in '$text'", text.contains(query), found)
    }
  }

  @Test fun `runs are split at anything that is not CJK`() {
    assertEquals(listOf("日本", "語"), CjkGrams.runs("日本 語"))
    assertEquals(listOf("東京", "大阪"), CjkGrams.runs("東京abc大阪"))
    assertEquals(emptyList<String>(), CjkGrams.runs("plain"))
  }
}
