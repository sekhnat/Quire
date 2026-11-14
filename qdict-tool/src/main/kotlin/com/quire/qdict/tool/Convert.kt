package com.quire.qdict.tool

import com.quire.qdict.ByteArraySource
import com.quire.qdict.Codec
import com.quire.qdict.DictionaryData
import com.quire.qdict.QdictReader
import com.quire.qdict.QdictWriter
import com.quire.qdict.WnLmf
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.system.exitProcess

/**
 * Converts an Open English WordNet release into the app's dictionary:
 *
 *   convert --input english-wordnet-2025.xml.gz --output oewn-2025.qdict
 *           [--budget 5242880] [--baseline dictionary/size-baseline.txt] [--measure]
 *
 * The output is decoded back entry by entry and compared with the source before it is written; any difference fails.
 * It also fails when the file is over [--budget] bytes, or more than 10% larger than the size the baseline file records.
 * `--measure` prints the size of the file under each candidate codec and block size, and how long a block takes to
 * decode, so the choice of codec can be checked against new releases.
 */
fun main(args: Array<String>) {
  val opts = args.toList().windowed(2, 1, partialWindows = true).filter { it[0].startsWith("--") }
    .associate { it[0].removePrefix("--") to it.getOrNull(1)?.takeIf { v -> !v.startsWith("--") } }
  val input = File(opts["input"] ?: fail("--input is required"))
  val output = File(opts["output"] ?: fail("--output is required"))
  val budget = opts["budget"]?.toLong() ?: DEFAULT_BUDGET
  val baseline = opts["baseline"]?.let(::File)

  val started = System.nanoTime()
  val source = input.inputStream().buffered().let { if (input.name.endsWith(".gz")) GZIPInputStream(it) else it }.use(WnLmf::read)
  val data = forApp(source)
  println("qdict: read OEWN ${data.release}: ${data.headwords.size} headwords, ${data.exceptions.size} irregular forms, " +
    "${data.synsets.size} synsets in ${(System.nanoTime() - started) / 1_000_000} ms")

  if ("measure" in opts) measure(data)

  val bytes = QdictWriter.write(data, CODEC, BLOCK, preset = true)
  verify(data, bytes)
  println("qdict: ${output.name} is ${bytes.size} bytes (%.2f MB), verified".format(bytes.size / 1_048_576.0))

  if (bytes.size > budget) fail("the dictionary is ${bytes.size} bytes, over the budget of $budget")
  baseline?.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { previous ->
    if (bytes.size > previous * 11 / 10) fail("the dictionary grew from $previous to ${bytes.size} bytes, more than 10%; update ${baseline.name} if this is intended")
  }
  output.parentFile?.mkdirs()
  output.writeBytes(bytes)
}

/** The default size budget: 5 MB. */
const val DEFAULT_BUDGET = 5L * 1024 * 1024

/**
 * The codec and block size, chosen by `--measure` on OEWN 2025: Deflate with a preset dictionary in 32 KB blocks is the
 * smallest file (XZ is no smaller and would add a decoder to the app), and a block still inflates in about 0.1 ms.
 */
val CODEC = Codec.Deflate
const val BLOCK = 32 * 1024

/**
 * What the app needs of the source: a synset whose only member is a single word lists no members, since a word is
 * never its own synonym and that one member is always the word being looked up.
 */
fun forApp(data: DictionaryData): DictionaryData =
  data.copy(synsets = data.synsets.map { if (it.members.size <= 1) it.copy(members = emptyList()) else it })

/** Reads [bytes] back with the app's own reader and checks that every entry is exactly what was written. */
fun verify(data: DictionaryData, bytes: ByteArray) {
  val reader = QdictReader(ByteArraySource(bytes), cacheBlocks = 4)
  check(reader.release == data.release) { "release differs" }
  check(reader.headwordCount == data.headwords.size && reader.synsetCount == data.synsets.size) { "counts differ" }
  var i = 0
  reader.allHeadwords().forEach { h -> if (h != data.headwords[i]) fail("headword $i differs: $h vs ${data.headwords[i]}"); i++ }
  if (i != data.headwords.size) fail("only $i headwords read back")
  val forms = reader.allExceptions().toList()
  if (forms != data.exceptions) fail("irregular forms differ")
  data.synsets.forEachIndexed { id, s -> if (reader.synset(id) != s) fail("synset $id differs") }
  // Spot-check the lookups the app makes: by key and by id.
  data.headwords.indices.step(maxOf(1, data.headwords.size / 500)).forEach { id ->
    val h = data.headwords[id]
    if (reader.headword(h.key)?.first != id || reader.headwordAt(id) != h) fail("lookup of '${h.key}' failed")
  }
}

/** Prints the file size under each candidate, and the time to decode one block on this machine. */
private fun measure(data: DictionaryData) {
  // Where the bytes go: each section written alone (the others empty), with the chosen codec.
  val empty = DictionaryData(data.release, emptyList(), emptyList(), emptyList())
  listOf(
    "headwords" to empty.copy(headwords = data.headwords),
    "irregular forms" to empty.copy(headwords = data.headwords, exceptions = data.exceptions),
    "synsets" to empty.copy(synsets = data.synsets),
    "synsets, no examples" to empty.copy(synsets = data.synsets.map { it.copy(examples = emptyList()) }),
  ).forEach { (name, d) -> println("qdict: section %-22s %.2f MB".format(name, QdictWriter.write(d).size / 1_048_576.0)) }
  println("qdict: candidate            block    size (MB)   decode/block")
  for (block in listOf(8 * 1024, 16 * 1024, 32 * 1024)) {
    for ((name, codec, preset) in listOf(Triple("none", Codec.None, false), Triple("deflate", Codec.Deflate, false), Triple("deflate+preset", Codec.Deflate, true))) {
      if (codec == Codec.None && block != 16 * 1024) continue
      val bytes = QdictWriter.write(data, codec, block, preset)
      val reader = QdictReader(ByteArraySource(bytes), cacheBlocks = 1)
      // Synsets far apart, so each read inflates a fresh block.
      val ids = (0 until data.synsets.size step maxOf(1, data.synsets.size / 200)).toList()
      ids.forEach(reader::synset)
      val t0 = System.nanoTime()
      ids.forEach(reader::synset)
      val perBlock = (System.nanoTime() - t0) / ids.size / 1000.0
      println("qdict: %-20s %5d KB  %8.2f   %8.1f µs".format(name, block / 1024, bytes.size / 1_048_576.0, perBlock))
    }
  }
  // XZ per block, estimated: the uncompressed file's sections compressed in 16 KB pieces (no reader for it in the app).
  val plain = QdictWriter.write(data, Codec.None, 16 * 1024, preset = false)
  val xz = plain.asList().chunked(16 * 1024).sumOf { chunk ->
    val out = ByteArrayOutputStream()
    XZOutputStream(out, LZMA2Options(9).apply { mode = LZMA2Options.MODE_NORMAL; niceLen = LZMA2Options.NICE_LEN_MAX; depthLimit = 512 }).use { it.write(chunk.toByteArray()) }
    out.size()
  }
  println("qdict: %-20s %5d KB  %8.2f   (estimate; needs a decoder library in the app)".format("xz 9e", 16, xz / 1_048_576.0))
}

private fun fail(message: String): Nothing {
  System.err.println("qdict: $message")
  exitProcess(1)
}
