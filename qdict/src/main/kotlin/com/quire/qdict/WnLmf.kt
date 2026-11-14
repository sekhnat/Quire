package com.quire.qdict

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Reads a WordNet in the Global WordNet Association's WN-LMF XML (what Open English WordNet publishes) into
 * [DictionaryData]: lemmas and their senses, irregular forms (`<Form>`), and synsets with their definition, examples
 * and members. Relations, pronunciations and everything else are left out.
 */
object WnLmf {

  fun read(input: InputStream): DictionaryData {
    val handler = Handler()
    val factory = SAXParserFactory.newInstance().apply {
      isNamespaceAware = false
      // The file names a DTD on the web; never fetch it.
      runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }
    val parser = factory.newSAXParser()
    parser.xmlReader.setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) }
    parser.parse(input, handler)
    return handler.build()
  }

  private class Entry(val id: String, val written: String, val pos: Pos) {
    val senses = ArrayList<String>()
    val forms = ArrayList<String>()
  }

  private class RawSynset(val id: String, val pos: Pos, val members: List<String>) {
    var gloss = ""
    val examples = ArrayList<String>()
  }

  private class Handler : DefaultHandler() {
    var release = ""
    val entries = ArrayList<Entry>()
    val synsets = ArrayList<RawSynset>()
    private var entry: Entry? = null
    private var synset: RawSynset? = null
    private val text = StringBuilder()
    private var collecting = false

    override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
      when (qName) {
        "Lexicon" -> release = a.getValue("version").orEmpty()
        "LexicalEntry" -> entry = null.also { pendingId = a.getValue("id") }
        "Lemma" -> {
          val pos = Pos.of(a.getValue("partOfSpeech")?.firstOrNull() ?: ' ')
          val written = a.getValue("writtenForm")
          if (pos != null && written != null) entry = Entry(pendingId.orEmpty(), written, pos)
        }
        "Form" -> a.getValue("writtenForm")?.let { entry?.forms?.add(it) }
        "Sense" -> a.getValue("synset")?.let { entry?.senses?.add(it) }
        "Synset" -> {
          val pos = Pos.of(a.getValue("partOfSpeech")?.firstOrNull() ?: ' ')
          synset = if (pos == null) null else RawSynset(a.getValue("id"), pos, a.getValue("members").orEmpty().split(' ').filter { it.isNotEmpty() })
        }
        "Definition", "Example" -> { text.setLength(0); collecting = synset != null }
      }
    }

    private var pendingId: String? = null

    override fun characters(ch: CharArray, start: Int, length: Int) { if (collecting) text.append(ch, start, length) }

    override fun endElement(uri: String?, localName: String?, qName: String) {
      when (qName) {
        "LexicalEntry" -> { entry?.let(entries::add); entry = null }
        "Synset" -> { synset?.let(synsets::add); synset = null }
        "Definition" -> { if (collecting && synset!!.gloss.isEmpty()) synset!!.gloss = clean(text); collecting = false }
        "Example" -> { if (collecting) clean(text).takeIf { it.isNotEmpty() }?.let { synset!!.examples.add(it) }; collecting = false }
      }
    }

    private fun clean(s: CharSequence) = s.toString().replace(Regex("\\s+"), " ").trim()

    fun build(): DictionaryData {
      // Synsets ordered by part of speech, then source id, and renumbered in that order.
      val ordered = synsets.sortedWith(compareBy<RawSynset> { it.pos.ordinal }.thenBy { it.id })
      val synsetIndex = ordered.withIndex().associate { (i, s) -> s.id to i }

      // Headwords: one per lower-case written form, merging entries (and parts of speech) that share it.
      data class Building(var display: String, val senses: LinkedHashMap<Pos, MutableList<Int>> = LinkedHashMap())
      val byKey = java.util.TreeMap<String, Building>(Utf8Order)
      val keyOfEntry = HashMap<String, String>()
      for (e in entries) {
        val key = keyOf(e.written)
        keyOfEntry[e.id] = key
        val b = byKey.getOrPut(key) { Building(e.written) }
        // A capitalised form ("Paris") is the display only when no lower-case entry shares the key.
        if (e.written == key) b.display = key
        val list = b.senses.getOrPut(e.pos) { ArrayList() }
        e.senses.mapNotNull(synsetIndex::get).forEach { if (it !in list) list += it }
      }
      val keys = byKey.keys.toList()
      val idOf = keys.withIndex().associate { (i, k) -> k to i }
      val headwords = keys.map { k -> byKey.getValue(k).let { b -> Headword(k, b.display, b.senses.filterValues { it.isNotEmpty() }) } }

      val exceptions = entries.flatMap { e ->
        val target = idOf.getValue(keyOf(e.written))
        e.forms.map { IrregularForm(keyOf(it), target, e.pos) }
      }.distinct().sortedWith(compareBy<IrregularForm, String>(Utf8Order) { it.form }.thenBy { it.headword }.thenBy { it.pos.ordinal })

      val result = ordered.map { s ->
        val members = s.members.mapNotNull { m -> keyOfEntry[m]?.let(idOf::get) }.distinct().sorted()
        Synset(s.pos, s.gloss, s.examples, members)
      }
      return DictionaryData(release, headwords, exceptions, result)
    }
  }

  /** The key a written form is stored and looked up under: lower case, single spaces, no underscores. */
  fun keyOf(written: String): String = written.lowercase().replace('_', ' ').replace(Regex("\\s+"), " ").trim()
}
