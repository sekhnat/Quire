package com.quire.reader.data.mobi

/**
 * Converts a KF8 (AZW3) book back into the XHTML it was built from. KF8 stores each file as a skeleton with fragments
 * cut out of it (the SKEL and FRAG indexes say where they go back in), and its stylesheets and SVG as further "flows".
 * Links and the table of contents name a fragment and an offset (`kindle:pos:fid`), which is turned into the nearest
 * anchor before that point; resources are `kindle:embed` and `kindle:flow` references.
 *
 * Everything here works on the text as Latin-1, one char per byte, because every position KF8 records is a byte offset;
 * a file is decoded only once its links are rewritten.
 */
internal class Kf8Converter(private val book: MobiBook, private val db: PalmDatabase, private val header: MobiHeader) {
  private class Part(val start: Long, val end: Long, val text: String) {
    val ids by lazy { matches(ID) }
    val names by lazy { matches(NAME) }
    val aids by lazy { matches(AID) }

    /** (end offset, value) of each match, in order. */
    private fun matches(regex: Regex) = regex.findAll(text).map { it.range.last + 1 to it.groupValues[1] }.toList()
  }

  private val title = book.metadata.title.ifEmpty { "Untitled" }
  private lateinit var parts: List<Part>
  private lateinit var fragmentStarts: List<Long>
  private val linkedAids = HashMap<Int, MutableSet<String>>()
  /** Flows (by number) that hold SVG; the others are stylesheets. */
  private val svgFlows = HashSet<Int>()

  fun convert(): EpubContent {
    val raw = String(readText(db, header), Charsets.ISO_8859_1)
    val flows = flowTable(raw.length).map { (start, end) -> raw.substring(start, end) }
    flows.forEachIndexed { n, flow -> if (n > 0 && flow.contains("<svg")) svgFlows += n }
    val fragments = MobiIndex.read(db, header.fragmentIndex, header.charset).entries
    fragmentStarts = fragments.map { it.key.trim().toLongOrNull() ?: -1L }
    parts = assemble(flows.first(), MobiIndex.read(db, header.skeletonIndex, header.charset).entries, fragments)
    if (parts.isEmpty()) throw MobiException("no KF8 text")

    // Resolve every link against the parts as assembled, then rewrite them.
    val linked = parts.map { part -> POS_FID.replace(part.text) { m -> resolve(m.groupValues[1], m.groupValues[2]) ?: "#" } }
    val ncx = MobiIndex.read(db, header.ncxIndex, header.charset)
    val toc = buildToc(ncx) { e ->
      val fid = e.value(6, 0)
      val off = e.value(6, 1)
      if (fid != null && off != null) resolve(fid, off) else e.value(1)?.let(::resolve)
    }.ifEmpty { listOf(TocNode(title, partName(0), emptyList())) }

    val documents = linked.mapIndexed { i, text ->
      val withIds = linkedAids[i]?.let { addAidIds(text, it) } ?: text
      // Kindle's own position attributes mean nothing outside it.
      val markup = decode(rewriteResources(AID_ATTRIBUTE.replace(withIds, "")))
      EpubFile(partName(i), Mobi6Converter.MEDIA_XHTML, Xhtml.document(markup, title).toByteArray(Charsets.UTF_8))
    }
    val styles = flows.drop(1).mapIndexedNotNull { i, flow ->
      val n = i + 1
      val text = decode(rewriteResources(flow))
      when {
        text.isBlank() -> null
        n in svgFlows -> EpubFile(flowName(n, "svg"), "image/svg+xml", Xhtml.clean(text.substring(text.indexOf("<svg"))).toByteArray(Charsets.UTF_8))
        else -> EpubFile(flowName(n, "css"), "text/css", text.replace("<![CDATA[", "").replace("]]>", "").toByteArray(Charsets.UTF_8))
      }
    }
    return EpubContent(book, documents, styles, toc)
  }

  /** Flow boundaries from the FDST record; one flow covering everything when there is none. */
  private fun flowTable(length: Int): List<Pair<Int, Int>> {
    val whole = listOf(0 to length)
    if (header.fdstIndex == NULL_INDEX || header.fdstIndex >= db.recordCount) return whole
    val fdst = runCatching { db.record(header.fdstIndex.toInt()) }.getOrNull() ?: return whole
    if (fdst.size < 12 || String(fdst, 0, 4, Charsets.ISO_8859_1) != "FDST") return whole
    val count = fdst.u32(8).toInt()
    if (count < 1 || 12 + count * 8 > fdst.size) return whole
    return (0 until count).map { i ->
      val start = fdst.u32(12 + i * 8).coerceIn(0, length.toLong()).toInt()
      start to fdst.u32(16 + i * 8).coerceIn(start.toLong(), length.toLong()).toInt()
    }
  }

  /** Each skeleton with its fragments put back at their insert positions; a fragment's text follows its skeleton's. */
  private fun assemble(text: String, skeletons: List<IndexEntry>, fragments: List<IndexEntry>): List<Part> {
    val out = ArrayList<Part>()
    var next = 0
    for (skeleton in skeletons) {
      val count = skeleton.value(1)?.toInt() ?: 0
      val start = skeleton.value(6, 0) ?: continue
      val length = skeleton.value(6, 1) ?: continue
      if (start + length > text.length) break
      val doc = StringBuilder(text.substring(start.toInt(), (start + length).toInt()))
      var base = start + length
      repeat(count) {
        val fragment = fragments.getOrNull(next) ?: return@repeat
        val insertAt = fragmentStarts[next++]
        val fragmentLength = fragment.value(6, 1) ?: 0L
        val end = minOf(base + fragmentLength, text.length.toLong())
        val piece = text.substring(minOf(base, end).toInt(), end.toInt())
        var at = (insertAt - start).coerceIn(0, doc.length.toLong()).toInt()
        // A badly made file can put the insert point inside a tag; move it past the tag.
        if (doc.lastIndexOf("<", at - 1) > doc.lastIndexOf(">", at - 1)) doc.indexOf(">", at).takeIf { it >= 0 }?.let { at = it + 1 }
        doc.insert(at, piece)
        base += fragmentLength
      }
      out += Part(start, base, doc.toString())
    }
    return out
  }

  private fun resolve(fid: String, off: String): String? {
    val f = fid.toIntOrNull(32) ?: return null
    val o = off.toLongOrNull(32) ?: return null
    return resolve(f.toLong(), o)
  }

  private fun resolve(fid: Long, off: Long): String? {
    val start = fragmentStarts.getOrNull(fid.toInt())?.takeIf { it >= 0 } ?: return null
    return resolve(start + off)
  }

  /** The href of the anchor nearest before [pos]: an `id`, else an `<a name>`, else an `aid` (given an id), else the file. */
  private fun resolve(pos: Long): String? {
    val i = parts.indexOfFirst { pos >= it.start && pos < it.end }.takeIf { it >= 0 }
      ?: parts.lastIndex.takeIf { pos == parts.last().end }
      ?: return null
    val part = parts[i]
    val text = part.text
    var p = (pos - part.start).toInt().coerceIn(0, text.length)
    val gt = text.indexOf('>', p)
    val lt = text.indexOf('<', p)
    // Inside a tag, or at one: the tag itself counts as before the point.
    if (gt >= 0 && (lt == p || (lt >= 0 && gt < lt))) p = gt + 1
    fun List<Pair<Int, String>>.lastBefore() = lastOrNull { it.first <= p }?.second
    val id = part.ids.lastBefore() ?: part.names.lastBefore() ?: part.aids.lastBefore()?.let { aid ->
      linkedAids.getOrPut(i) { HashSet() } += aid
      "aid-$aid"
    }
    return partName(i) + (id?.let { "#" + fragment(it) } ?: "")
  }

  /** Gives each linked-to `aid` an id to link to (a tag that is linked by its aid has no id of its own). */
  private fun addAidIds(text: String, aids: Set<String>): String {
    val out = StringBuilder(text.length + aids.size * 24)
    var last = 0
    for (m in AID.findAll(text)) {
      val aid = m.groupValues[1]
      if (aid !in aids) continue
      val end = m.range.last + 1
      out.append(text, last, end).append(" id=\"aid-").append(aid).append('"')
      last = end
    }
    return out.append(text, last, text.length).toString()
  }

  private fun rewriteResources(text: String): String = RESOURCE.replace(text) { m ->
    val n = m.groupValues[2].toIntOrNull(32) ?: return@replace ""
    if (m.groupValues[1] == "embed") book.resources.get(n)?.href.orEmpty() else flowName(n, if (n in svgFlows) "svg" else "css")
  }

  private fun decode(latin1: String): String = Xhtml.clean(String(latin1.toByteArray(Charsets.ISO_8859_1), header.charset))

  private fun partName(i: Int) = "part%04d.xhtml".format(i)

  private fun flowName(n: Int, ext: String) = "flow%04d.%s".format(n, ext)

  companion object {
    private val POS_FID = Regex("""kindle:pos:fid:([0-9A-Va-v]{4}):off:([0-9A-Va-v]{10})""")
    private val RESOURCE = Regex("""kindle:(embed|flow):([0-9A-Va-v]{4})(?:\?mime=[^'"()\s]*)?""")
    private val ID = Regex("""<[^>]+\s(?:id|ID)\s*=\s*['"]([^'"]+)['"]""")
    private val NAME = Regex("""<\s*a\s*\s(?:name|NAME)\s*=\s*['"]([^'"]+)['"]""")
    private val AID = Regex("""<[^>]+\s(?:aid|AID)\s*=\s*['"]([^'"]+)['"]""")
    private val AID_ATTRIBUTE = Regex("""\s(?:aid|AID)\s*=\s*(?:"[^"]*"|'[^']*')""")

    /** [id] (bytes as Latin-1 chars) percent-encoded where a URL fragment cannot hold it as is. */
    fun fragment(id: String): String = buildString {
      for (c in id) {
        if (c.isLetterOrDigit() && c.code < 0x80 || c in "-._~!$()*+,;=:@/?") append(c) else append("%%%02X".format(c.code and 0xFF))
      }
    }
  }
}
