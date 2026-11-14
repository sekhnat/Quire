package com.quire.reader.reader

import com.quire.reader.data.fonts.UserFont
import com.quire.reader.data.fonts.UserFontFile
import java.io.File

/**
 * The user's fonts as the reader's WebViews see them, kept current by the app (see QuireApplication). Documents are
 * served with `@font-face` rules for every font listed here (HtmlInjector), and `fonts/user/<name>` answers only the
 * files listed here, by exact name: a request from book content can never reach any other file.
 */
object UserFontRegistry {
  @Volatile var fonts: List<UserFont> = emptyList()
  @Volatile var dir: File? = null

  /** The file behind `fonts/user/<name>`, only when it is a listed file of a listed font. */
  fun file(name: String): File? {
    val root = dir ?: return null
    val listed = fonts.any { f -> f.files.any { it.name == name } }
    return if (listed) File(root, name).takeIf { it.isFile } else null
  }

  /** `@font-face` rules for [fonts], with each file at [urlOf] its name. */
  fun fontFaceCss(fonts: List<UserFont> = this.fonts, urlOf: (String) -> String): String = fonts.joinToString("\n") { font ->
    font.files.joinToString("\n") { file ->
      val weight = if (file.minWeight == file.maxWeight) "${file.minWeight}" else "${file.minWeight} ${file.maxWeight}"
      "@font-face { font-family: \"${font.cssName}\"; src: url(\"${urlOf(file.name)}\") format(\"${format(file)}\"); " +
        "font-style: ${if (file.italic) "italic" else "normal"}; font-weight: $weight; }"
    }
  }

  private fun format(file: UserFontFile) = if (file.name.endsWith(".otf")) "opentype" else "truetype"

  /** The MIME type a font file is served as. */
  fun mimeType(name: String) = if (name.endsWith(".otf")) "font/otf" else "font/ttf"
}
