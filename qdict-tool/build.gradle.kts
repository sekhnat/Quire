import java.net.URI
import java.security.MessageDigest

// Converts an Open English WordNet release (WN-LMF XML) into the app's `.qdict` file, verifies the result entry by
// entry, measures the candidate codecs and enforces the size budget. Runs at build time only; nothing here ships.
plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  jvmToolchain(21)
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
  mainClass.set("com.quire.qdict.tool.ConvertKt")
}

dependencies {
  implementation(project(":qdict"))
  implementation(libs.xz)
  testImplementation(libs.junit)
}

// ── The dictionary the app ships ─────────────────────────────────────────────────────────────────────────────────────
// Open English WordNet, CC BY 4.0 (checked in the 2025 release's own Lexicon element). The release is pinned by URL and
// SHA-256; it is downloaded once into this build directory and converted into the app's `.qdict`.

val oewnRelease = "2025"
val oewnUrl = "https://github.com/globalwordnet/english-wordnet/releases/download/2025-edition/english-wordnet-2025.xml.gz"
val oewnSha256 = "9ca6d1dcb75f822fdd66617f7d9da48142ace38dd544d6ad5e2feca1674ad3fe"

/** Downloads [url] to [output] unless it is already there with the right checksum; a mismatch fails the build. */
abstract class DownloadChecked : DefaultTask() {
  @get:Input abstract val url: Property<String>
  @get:Input abstract val sha256: Property<String>
  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun download() {
    val out = output.get().asFile
    fun sha(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
      f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
      md.digest().joinToString("") { "%02x".format(it) }
    }
    if (out.isFile && sha(out) == sha256.get()) return
    out.parentFile.mkdirs()
    val part = File(out.path + ".part")
    URI(url.get()).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val got = sha(part)
    if (got != sha256.get()) {
      part.delete()
      throw GradleException("Checksum mismatch for ${url.get()}: expected ${sha256.get()}, got $got")
    }
    if (!part.renameTo(out)) throw GradleException("Could not move the download into place")
  }
}

val downloadOewn = tasks.register<DownloadChecked>("downloadOewn") {
  url.set(oewnUrl)
  sha256.set(oewnSha256)
  output.set(layout.buildDirectory.file("oewn/english-wordnet-$oewnRelease.xml.gz"))
}

val convertOewn = tasks.register<JavaExec>("convertOewn") {
  description = "Converts Open English WordNet into the app's bundled dictionary, verifies it and checks its size."
  val input = downloadOewn.flatMap { it.output }
  val output = layout.buildDirectory.file("dictionary/oewn-$oewnRelease.qdict")
  val baseline = rootProject.layout.projectDirectory.file("dictionary/size-baseline.txt")
  inputs.file(input)
  inputs.file(baseline).optional()
  outputs.file(output)
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.quire.qdict.tool.ConvertKt")
  argumentProviders += CommandLineArgumentProvider {
    listOf("--input", input.get().asFile.path, "--output", output.get().asFile.path, "--baseline", baseline.asFile.path)
  }
}
