# Terminal-first Android development

## Environment

- SDK: `~/Android/Sdk`; Android 16 / API 36; build tools 36.0.0.
- Build JDK: `/usr/lib/jvm/java-21-openjdk` (21). System Java is 27; do not use it for these builds.
- Emulator: `Android_API_36` (Pixel 7, x86_64, KVM, 4 cores, 4 GB RAM).
- Full guide: `~/.config/android/README.md`; local overview: `ANDROID.md`.

Activate in Zsh (new interactive terminals already do this):

```zsh
source ~/.config/android/env.zsh
```

**Bash-based runners must use Zsh**, not source this Zsh-specific file directly:

```bash
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'
```

## Build and test

Run from the Android project root. Always use its Gradle wrapper; no global
Gradle or standalone Kotlin compiler is needed. Templates can automatically
provision a Java 17 compilation toolchain while Gradle runs on Java 21.

```zsh
./gradlew assembleDebug test lint
./gradlew connectedDebugAndroidTest  # Requires a running device/emulator.
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Run on the emulator

```zsh
emulator -avd Android_API_36         # Separate terminal; wait for Android to boot.
adb devices
./gradlew installDebug
adb shell am start -n 'APPLICATION_ID/ACTIVITY_CLASS'
```

Replace the launch component with the project's actual application ID and
launcher activity. With multiple devices, select one using `adb -s SERIAL`.
Stop the default emulator with `adb -s emulator-5554 emu kill`.

## SDK management

Prefer `android sdk list` and `android sdk install PACKAGE`;
`sdkmanager` is available but deprecated. Use `emulator -accel-check` to check KVM.
Wait for SDK installations to finish before other Android tooling: they share an SDK lock.
First builds need internet access; subsequent builds reuse cached Gradle, toolchains, and dependencies.

## The app (Quire, an EPUB reader)

- Application ID `com.quire.reader`, minSdk 30 (all-files access starts at Android 11), Kotlin + Jetpack Compose. Design source: the "Quire Reader" Claude Design project (Nocturne design system; tokens in `theme/Tokens.kt`).
- `data/` is the library: Room (`data/db`), the folder scanner and Calibre `metadata.opf` parser (`data/scan`), `LibraryRepository`, `SettingsStore` (DataStore). The library text index is a second Room database, `quire-index.db` (`IndexDatabase`), on the bundled SQLite driver because FTS5 is missing from the platform's; `data/index` writes it through `IndexStore` (the FTS5 tables have no triggers, so every write keeps them in step by hand) and searches it with `TextSearcher`. The two databases cannot be joined in SQL, so book filters and index state are joined in Kotlin. The release build keeps only `arm64-v8a` and `x86_64` native libraries. `reader/` wraps Readium (`ReaderSession`, `EpubHost`, `PrefsMapper`). `ui/` has the Compose screens and their state holders: `QuireViewModel` keeps only app and navigation state, and each screen's holder (`LibraryState`, `DetailState`, `ReaderState`, `SettingsState`, `OnboardingState`) is owned by its `Destination` and closed when it is left. Holders declare narrow ports that `ui/Features.kt` adapts to the repositories, so tests build them from fakes; user writes go on the `persist` scope so leaving a screen never drops them.
- MOBI and AZW3 (`data/mobi`): Readium cannot open them, so `PublicationLoader` converts each to an EPUB on first open (`MobiBook.writeEpub`: MOBI 6 split at page breaks, KF8 rebuilt from its skeleton/fragment indexes) and `ConvertedBooks` keeps the copies in `cacheDir/converted`, least recently used first within 256 MB. The scanner reads their metadata and cover straight from the header (no conversion). `BookFormats` lists the extensions and keeps one file per book name (EPUB over AZW3 over MOBI). Positions, highlights and the text index name the converted documents (`OEBPS/part0003.xhtml`), so a change to the converter must keep how text is split and named; bump `CONVERTER_VERSION` when its output changes. Fixtures and how they were made: `app/src/test/resources/mobi`.
- **Readium is pinned to 3.3.0.** 3.4.0 needs compileSdk 37, which AGP 9.0.1 does not support. Coil is pinned to 3.5.0 for the same reason (3.6.x pulls Compose 1.12 / compileSdk 37). Check `checkDebugAarMetadata` errors before bumping either.
- Scroll mode's live window is decided in `assets/quire/continuous-scroll.js` (`planWindow`, a pure function tested by `ShellWindowPolicyTest`): it leans into fling direction and predicted stop, evicts by estimated document weight, and shrinks with the memory-pressure tier from `navigator/epub/ScrollMemoryPressure.kt`. Its telemetry exists only in the `benchmark` build type (app id `com.quire.reader.bench`, `BuildConfig.SCROLL_TELEMETRY`). Benchmark on the emulator: `adb push ORV.epub /data/local/tmp/quire-bench/`, then `./gradlew connectedBenchmarkAndroidTest -PtestBuildType=benchmark -Pandroid.testInstrumentationRunnerArguments.class=com.quire.reader.ui.ScrollWindowBenchmark`; reports land in `/data/local/tmp/quire-bench/results`. The fling and long tests run the fixed (`static`) and `adaptive` policies side by side, about 20 and 35 minutes; nothing heavy should run on the host meanwhile.
- The app reads books straight from shared storage and needs "All files access". On the emulator: `adb shell appops set com.quire.reader MANAGE_EXTERNAL_STORAGE allow`.
- Testing the library on the emulator: `adb push` EPUBs into `/sdcard/Books` and Calibre-style trees (`Author/Title (id)/book.epub` + `metadata.opf` + `cover.jpg`) into `/sdcard/Calibre Library`, then relaunch; the app rescans on start. Scan results are logged under the `LibraryScanner` tag.
- Fonts: reading fonts live in `assets/fonts` (served to Readium's WebView) and are listed in `reader/ReaderFonts.kt`; books store the font as an index into that list, so only ever append. The fonts the UI also uses (Literata, Source Serif, Atkinson, Inter) have copies in `res/font`; keep those in step. The others are previewed straight from `assets/fonts`. New fonts must be unmodified (their OFL reserves the names) and ship their license in `assets/licenses/fonts`.
