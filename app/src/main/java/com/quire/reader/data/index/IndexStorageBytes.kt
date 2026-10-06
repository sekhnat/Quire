package com.quire.reader.data.index

/** Measured disk usage of the authoritative library and the rebuildable search index. */
data class IndexStorageBytes(val library: Long, val index: Long)
