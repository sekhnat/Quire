package com.quire.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [FolderEntity::class, BookEntity::class, BookTagEntity::class, BookStateEntity::class, BookmarkEntity::class, HighlightEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class QuireDatabase : RoomDatabase() {
  abstract fun folders(): FolderDao
  abstract fun books(): BookDao
  abstract fun states(): StateDao
  abstract fun annotations(): AnnotationDao

  companion object {
    fun create(context: Context): QuireDatabase =
      Room.databaseBuilder(context.applicationContext, QuireDatabase::class.java, "quire.db").build()
  }
}
