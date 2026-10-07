package com.quire.reader.ui

import com.quire.reader.data.backup.ImportResult
import com.quire.reader.data.backup.MergeOutcome
import com.quire.reader.data.scan.ScanResult

/** "1 book" or "n books". */
fun books(n: Int) = "$n ${if (n == 1) "book" else "books"}"

/** What a scan changed, for a toast. */
fun describe(r: ScanResult): String = when {
  r.added == 0 && r.removed == 0 && r.updated == 0 && r.moved == 0 -> "Library is up to date"
  else -> listOfNotNull(
    if (r.added > 0) "${r.added} new ${if (r.added == 1) "book" else "books"}" else null,
    if (r.updated > 0) "${r.updated} updated" else null,
    if (r.moved > 0) "${r.moved} moved" else null,
    if (r.removed > 0) "${r.removed} removed" else null,
  ).joinToString(" · ")
}

/** Whether a quiet background scan found something worth mentioning. */
fun ScanResult.noticeable(): Boolean = added > 0 || removed > 0 || moved > 0

/** What importing reading data added, for a toast. */
fun importSummary(r: ImportResult): String = when {
  !r.changed -> "Nothing to import"
  else -> listOfNotNull(
    if (r.matched > 0) "${r.matched} book${if (r.matched == 1) "" else "s"} updated" else null,
    if (r.tombstoned > 0) "${r.tombstoned} kept under Missing books" else null,
    if (r.highlightsAdded > 0) "${r.highlightsAdded} highlight${if (r.highlightsAdded == 1) "" else "s"}" else null,
    if (r.bookmarksAdded > 0) "${r.bookmarksAdded} bookmark${if (r.bookmarksAdded == 1) "" else "s"}" else null,
    if (r.tagsAdded > 0) "${r.tagsAdded} tag${if (r.tagsAdded == 1) "" else "s"}" else null,
  ).joinToString(" · ")
}

/** What merging a full backup added, for a toast. */
fun mergeSummary(o: MergeOutcome): String {
  val added = if (o.booksAdded > 0) "${books(o.booksAdded)} added" else null
  return if (added == null) importSummary(o.result) else if (!o.result.changed) added else "$added · ${importSummary(o.result)}"
}
