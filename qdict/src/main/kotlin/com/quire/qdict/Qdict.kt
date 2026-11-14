package com.quire.qdict

/*
 * The `.qdict` format: a read-only dictionary meant to be shipped compressed and read in place, never unpacked.
 *
 *   "QDCT"  u8 version  u8 codec  u32 tableLength  table  sections…
 *
 * The table (plain bytes, read once at open) holds the release name, the counts, the shared preset dictionary's place,
 * and one block list per section. Every section is a run of blocks of about [QdictWriter.DEFAULT_BLOCK] bytes before
 * compression, each compressed on its own, so a lookup inflates one block:
 *
 *   headwords   keys sorted in UTF-8 byte order, front-coded (each key as the length it shares with the one before
 *               and the rest; every block starts whole). A headword's id is its rank. Its value: the display form
 *               (empty when it is the key), then per part of speech its synset ids in sense order (zigzag deltas).
 *   exceptions  irregular forms, stored like the headwords; the value lists (headword id, part of speech).
 *   synsets     records in id order: part of speech, definition, examples, member headword ids (deltas).
 *
 * A block-list entry gives the block's first key (or first id), its first ordinal, and where it is: offset, compressed
 * and raw length. Integers are varints except the four fixed bytes of the table length.
 */

/** Random access to the bytes of a `.qdict` file, wherever they live (an asset inside the APK, a file, memory). */
interface RandomAccessSource {
  val size: Long
  /** [length] bytes from [offset]; throws when they are not all there. */
  fun read(offset: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
  override val size: Long get() = bytes.size.toLong()
  override fun read(offset: Long, length: Int): ByteArray {
    if (offset < 0 || length < 0 || offset + length > bytes.size) throw QdictException("read past the end")
    return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
  }
}

internal class BlockRef(val firstKey: String, val firstOrdinal: Int, val offset: Long, val compressed: Int, val raw: Int)

object QdictWriter {
  const val VERSION = 1
  const val DEFAULT_BLOCK = 16 * 1024
  const val PRESET_BYTES = 32 * 1024

  /** The whole file for [data]. */
  fun write(data: DictionaryData, codec: Codec = Codec.Deflate, blockSize: Int = DEFAULT_BLOCK, preset: Boolean = true): ByteArray {
    val ids = data.headwords
    require(ids.zipWithNext().all { (a, b) -> Utf8Order.compare(a.key, b.key) < 0 }) { "headwords must be sorted and unique" }

    val wordRecords = ids.map { h -> h.key to headwordValue(h) }
    val exceptionRecords = data.exceptions.groupBy { it.form }.toSortedMap(Utf8Order).map { (form, list) ->
      form to Out().apply { varint(list.size); list.forEach { varint(it.headword); byte(it.pos.code.code) } }.toByteArray()
    }
    val synsetRecords = data.synsets.map { synsetRecord(it) }

    val presetBytes = if (preset && codec == Codec.Deflate) buildPreset(synsetRecords) else null

    val body = Out()
    // Offsets are absolute; the table's own size is not known until the sections are, so sections are laid out first
    // from position 0 of the body and shifted when the file is assembled.
    val presetOffset = body.size.toLong()
    presetBytes?.let(body::bytes)
    val wordBlocks = keyedBlocks(wordRecords, blockSize, codec, presetBytes, body)
    val exceptionBlocks = keyedBlocks(exceptionRecords, blockSize, codec, presetBytes, body)
    val synsetBlocks = recordBlocks(synsetRecords, blockSize, codec, presetBytes, body)

    fun table(shift: Long): ByteArray = Out().apply {
      string(data.release)
      varint(ids.size); varint(exceptionRecords.size); varint(data.synsets.size)
      varint(blockSize)
      varint((presetOffset + shift).toInt()); varint(presetBytes?.size ?: 0)
      listOf(wordBlocks, exceptionBlocks, synsetBlocks).forEach { blocks ->
        varint(blocks.size)
        blocks.forEach { b -> string(b.firstKey); varint(b.firstOrdinal); varint((b.offset + shift).toInt()); varint(b.compressed); varint(b.raw) }
      }
    }.toByteArray()

    // The table's length depends on the shift through the varint offsets; settle it in a couple of passes.
    var shift = 0L
    var t = table(shift)
    repeat(4) {
      val next = HEADER_FIXED.toLong() + t.size
      if (next == shift) return@repeat
      shift = next
      t = table(shift)
    }
    check(HEADER_FIXED.toLong() + t.size == shift) { "table did not settle" }
    return Out().apply {
      bytes(MAGIC); byte(VERSION); byte(codec.id); u32(t.size); bytes(t); bytes(body.toByteArray())
    }.toByteArray()
  }

  internal val MAGIC = "QDCT".toByteArray(Charsets.US_ASCII)
  internal const val HEADER_FIXED = 4 + 1 + 1 + 4

  private fun headwordValue(h: Headword): ByteArray = Out().apply {
    string(if (h.display == h.key) "" else h.display)
    val byPos = Pos.entries.filter { h.senses[it]?.isNotEmpty() == true }
    varint(byPos.size)
    byPos.forEach { pos ->
      val list = h.senses.getValue(pos)
      byte(pos.code.code)
      varint(list.size)
      var prev = 0
      list.forEach { id -> varint(zigzag(id - prev)); prev = id }
    }
  }.toByteArray()

  private fun synsetRecord(s: Synset): ByteArray = Out().apply {
    byte(s.pos.code.code)
    string(s.gloss)
    varint(s.examples.size); s.examples.forEach(::string)
    varint(s.members.size)
    var prev = 0
    s.members.forEach { id -> varint(zigzag(id - prev)); prev = id }
  }.toByteArray()

  /** Front-coded key/value records in blocks; each block's first key is stored whole. */
  private fun keyedBlocks(records: List<Pair<String, ByteArray>>, blockSize: Int, codec: Codec, preset: ByteArray?, body: Out): List<BlockRef> {
    val refs = ArrayList<BlockRef>()
    var block = Out()
    var first: String? = null
    var firstOrdinal = 0
    var prevKey = ByteArray(0)
    fun flush() {
      val raw = block.toByteArray()
      if (raw.isEmpty()) return
      val packed = Blocks.compress(codec, raw, preset)
      refs += BlockRef(first!!, firstOrdinal, body.size.toLong(), packed.size, raw.size)
      body.bytes(packed)
      block = Out(); first = null; prevKey = ByteArray(0)
    }
    records.forEachIndexed { i, (key, value) ->
      val k = key.toByteArray(Charsets.UTF_8)
      if (first == null) { first = key; firstOrdinal = i }
      val shared = commonPrefix(prevKey, k)
      block.varint(shared); block.varint(k.size - shared); block.bytes(k.copyOfRange(shared, k.size))
      block.varint(value.size); block.bytes(value)
      prevKey = k
      if (block.size >= blockSize) flush()
    }
    flush()
    return refs
  }

  private fun recordBlocks(records: List<ByteArray>, blockSize: Int, codec: Codec, preset: ByteArray?, body: Out): List<BlockRef> {
    val refs = ArrayList<BlockRef>()
    var block = Out()
    var firstOrdinal = 0
    fun flush(next: Int) {
      val raw = block.toByteArray()
      if (raw.isNotEmpty()) {
        val packed = Blocks.compress(codec, raw, preset)
        refs += BlockRef("", firstOrdinal, body.size.toLong(), packed.size, raw.size)
        body.bytes(packed)
      }
      block = Out(); firstOrdinal = next
    }
    records.forEachIndexed { i, r ->
      block.varint(r.size); block.bytes(r)
      if (block.size >= blockSize) flush(i + 1)
    }
    flush(records.size)
    return refs
  }

  /**
   * A preset dictionary for Deflate: Deflate looks back at most 32 KB, so small blocks start with nothing to refer to.
   * A sample of records spread through the whole set gives every block the common words and phrasing of definitions.
   */
  private fun buildPreset(records: List<ByteArray>): ByteArray {
    if (records.isEmpty()) return ByteArray(0)
    val out = java.io.ByteArrayOutputStream()
    val step = maxOf(1, records.size / 400)
    var i = records.size / 2 % step
    while (out.size() < PRESET_BYTES && i < records.size) { out.write(records[i]); i += step }
    return out.toByteArray().copyOf(minOf(out.size(), PRESET_BYTES))
  }

  private fun commonPrefix(a: ByteArray, b: ByteArray): Int {
    var n = 0
    while (n < a.size && n < b.size && a[n] == b[n]) n++
    return n
  }
}

internal fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)
internal fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

/**
 * Reads a `.qdict` file in place. Opening reads only the header and the block lists (a few kilobytes); a lookup inflates
 * the one block it needs, and the last [cacheBlocks] inflated blocks are kept. Thread-safe.
 */
class QdictReader(private val source: RandomAccessSource, private val cacheBlocks: Int = 16) {
  val release: String
  val headwordCount: Int
  val exceptionCount: Int
  val synsetCount: Int
  val codec: Codec
  private val preset: ByteArray?
  private val words: List<BlockRef>
  private val exceptions: List<BlockRef>
  private val synsets: List<BlockRef>

  init {
    if (source.size < QdictWriter.HEADER_FIXED) throw QdictException("too short for a dictionary")
    val head = In(source.read(0, QdictWriter.HEADER_FIXED))
    if (!head.bytes(4).contentEquals(QdictWriter.MAGIC)) throw QdictException("not a .qdict file")
    val version = head.byte()
    if (version != QdictWriter.VERSION) throw QdictException("unsupported version $version")
    codec = Codec.of(head.byte())
    val tableLength = head.u32()
    if (tableLength < 0 || QdictWriter.HEADER_FIXED.toLong() + tableLength > source.size) throw QdictException("table runs past the end")
    val t = In(source.read(QdictWriter.HEADER_FIXED.toLong(), tableLength))
    release = t.string()
    headwordCount = t.varint(); exceptionCount = t.varint(); synsetCount = t.varint()
    t.varint() // block size the file was written with; informational
    val presetOffset = t.varint().toLong()
    val presetLength = t.varint()
    preset = if (presetLength > 0) source.read(presetOffset, presetLength) else null
    fun blocks(): List<BlockRef> = List(t.varint().also { if (it > 1_000_000) throw QdictException("too many blocks") }) {
      val ref = BlockRef(t.string(), t.varint(), t.varint().toLong(), t.varint(), t.varint())
      if (ref.offset + ref.compressed > source.size || ref.raw > 64 * 1024 * 1024) throw QdictException("block past the end")
      ref
    }
    words = blocks(); exceptions = blocks(); synsets = blocks()
  }

  /** The headword [key] (lower-case, as stored), or null. */
  fun headword(key: String): Pair<Int, Headword>? = keyed(words, key)?.let { (ordinal, value) -> ordinal to parseHeadword(key, value) }

  /** Whether [key] is a headword with [pos] among its parts of speech. */
  fun has(key: String, pos: Pos): Boolean = headword(key)?.second?.senses?.containsKey(pos) == true

  /** The headword with id [id]. */
  fun headwordAt(id: Int): Headword {
    if (id !in 0 until headwordCount) throw QdictException("no headword $id")
    val (key, value) = byOrdinal(words, id)
    return parseHeadword(key, value)
  }

  /** The irregular forms of [form]: which headwords, as which part of speech. */
  fun exceptionsOf(form: String): List<Pair<Int, Pos>> {
    val (_, value) = keyed(exceptions, form) ?: return emptyList()
    val r = In(value)
    return List(r.varint()) { r.varint() to (Pos.of(r.byte().toChar()) ?: throw QdictException("bad part of speech")) }
  }

  fun synset(id: Int): Synset {
    if (id !in 0 until synsetCount) throw QdictException("no synset $id")
    val ref = synsets[blockIndexByOrdinal(synsets, id)]
    val r = In(block(ref))
    repeat(id - ref.firstOrdinal) { val length = r.varint(); r.pos += length }
    val rec = In(r.bytes(r.varint()))
    val pos = Pos.of(rec.byte().toChar()) ?: throw QdictException("bad part of speech")
    val gloss = rec.string()
    val examples = List(rec.varint()) { rec.string() }
    var prev = 0
    val members = List(rec.varint()) { prev += unzigzag(rec.varint()); prev }
    return Synset(pos, gloss, examples, members)
  }

  /** Every headword in order, for verification; decodes each block once. */
  fun allHeadwords(): Sequence<Headword> = words.asSequence().flatMap { ref -> entries(ref).map { (k, v) -> parseHeadword(k, v) } }

  fun allExceptions(): Sequence<IrregularForm> = exceptions.asSequence().flatMap { ref ->
    entries(ref).flatMap { (form, v) -> val r = In(v); List(r.varint()) { IrregularForm(form, r.varint(), Pos.of(r.byte().toChar())!!) } }
  }

  private fun parseHeadword(key: String, value: ByteArray): Headword {
    val r = In(value)
    val display = r.string().ifEmpty { key }
    val senses = LinkedHashMap<Pos, List<Int>>()
    repeat(r.varint()) {
      val pos = Pos.of(r.byte().toChar()) ?: throw QdictException("bad part of speech")
      var prev = 0
      senses[pos] = List(r.varint()) { prev += unzigzag(r.varint()); prev }
    }
    return Headword(key, display, senses)
  }

  private fun keyed(blocks: List<BlockRef>, key: String): Pair<Int, ByteArray>? {
    if (blocks.isEmpty()) return null
    // The last block whose first key is at or before [key].
    var lo = 0
    var hi = blocks.size - 1
    if (Utf8Order.compare(key, blocks[0].firstKey) < 0) return null
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (Utf8Order.compare(blocks[mid].firstKey, key) <= 0) lo = mid else hi = mid - 1
    }
    val ref = blocks[lo]
    entries(ref).forEachIndexed { i, (k, v) ->
      val c = Utf8Order.compare(k, key)
      if (c == 0) return (ref.firstOrdinal + i) to v
      if (c > 0) return null
    }
    return null
  }

  private fun byOrdinal(blocks: List<BlockRef>, ordinal: Int): Pair<String, ByteArray> {
    val ref = blocks[blockIndexByOrdinal(blocks, ordinal)]
    return entries(ref).drop(ordinal - ref.firstOrdinal).firstOrNull() ?: throw QdictException("no entry $ordinal")
  }

  private fun blockIndexByOrdinal(blocks: List<BlockRef>, ordinal: Int): Int {
    var lo = 0
    var hi = blocks.size - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (blocks[mid].firstOrdinal <= ordinal) lo = mid else hi = mid - 1
    }
    return lo
  }

  private fun entries(ref: BlockRef): Sequence<Pair<String, ByteArray>> = sequence {
    val r = In(block(ref))
    var prev = ByteArray(0)
    while (!r.atEnd) {
      val shared = r.varint()
      if (shared > prev.size) throw QdictException("bad front coding")
      val rest = r.bytes(r.varint())
      val key = prev.copyOf(shared) + rest
      yield(String(key, Charsets.UTF_8) to r.bytes(r.varint()))
      prev = key
    }
  }

  private val cache = object : LinkedHashMap<Long, ByteArray>(cacheBlocks * 2, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > cacheBlocks
  }

  private fun block(ref: BlockRef): ByteArray = synchronized(cache) {
    cache.getOrPut(ref.offset) { Blocks.decompress(codec, source.read(ref.offset, ref.compressed), ref.raw, preset) }
  }
}
