# Signed release builds with stable keystore + auto versionCode

## Context

Every CI artifact and tagged GitHub release today is a **debug APK**. GitHub runners start
fresh, so each build is signed with a newly generated debug key: installing a newer APK
over an older one fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only workaround
— uninstall first — wipes the Room library database and reader settings. On top of that,
`versionCode = 1` is hard-coded, so even same-signed builds could never upgrade, and R8 is
disabled (`isMinifyEnabled = false`), so release builds ship with the full debug-size dex.

Fix: store a long-lived **release keystore as repository secrets**, build a **signed
release variant** in CI, **derive versionCode from the CI run number**, and **enable R8**
with keep rules for the vendored Readium navigator's JavaScript interfaces and
kotlinx-serialization.

Note: devices that already have a debug-signed Quire will still face a **one-time**
uninstall (signature mismatch is unavoidable once); after that, upgrades install in place.

## Current state (findings)

- `app/build.gradle.kts`: `versionCode = 1`, `versionName = "1.0"` hard-coded; `release`
  buildType has `isMinifyEnabled = false` and references `proguard-rules.pro`, which
  **does not exist yet**; no `signingConfigs` block anywhere.
- `.github/workflows/build.yml`: `assembleDebug test lint` on every event; uploads
  `app-debug.apk` as artifact `quire-debug-apk`; on `v*` tags a `release` job downloads
  that same debug artifact and attaches it to a GitHub release via `gh release create`.
- Vendored Readium navigator (`app/src/main/java/com/quire/reader/navigator/`) registers
  JS bridges via `addJavascriptInterface(obj, "Android" | "QuireShell" | "QuireBookBridge")`
  in `R2BasicWebView.kt`, `ContinuousBookWebView.kt` (inner `ShellBridge`/`QuireBookBridge`),
  `EpubNavigatorFragment.kt`, `R2EpubPageFragment.kt`, `R2FXLPageFragment.kt` — all with
  `@android.webkit.JavascriptInterface` methods called by name from injected JS.
- kotlinx-serialization in app code: `data/ReaderPrefs.kt` (incl. enums `ReadMode`,
  `TextAlignPref`, `theme.ReaderTheme`), `navigator/epub/EpubPreferences.kt`
  (`EpubPreferencesSerializer.kt` calls `.serializer()` directly). Readium 3.3.0 AARs ship
  **no** consumer ProGuard rules; kotlinx-serialization-core 1.11.0 ships bundled R8 rules
  for serializers — the app-level rules below are explicit insurance per the request.
- No other reflective code (no `Class.forName`; `KClass` uses are plain `is` checks).
- `gradle.properties` enables configuration cache — signing config must read env vars via
  `providers.environmentVariable(...)` to stay compatible.
- README "Install" section documents the debug-artifact flow and must be updated.
- `gh` CLI is authenticated as `sekhnat` with `repo` scope → secrets via `gh secret set`.

## Decisions (confirmed with user)

1. **versionCode = `GITHUB_RUN_NUMBER` for every CI build** (main pushes and tags alike);
   strictly monotonic, any newer artifact installs over any older one.
   versionName: tag push → tag without `v` (e.g. `v1.2.3` → `1.2.3`); other pushes →
   `dev-<short sha>`. Local builds fall back to versionCode 1 / versionName 1.0.
2. **Main-branch CI artifacts are also release-signed** (same keystore, run-number
   versionCode). Push events publish only the release APK artifact; PR events keep the
   debug artifact (secrets not available from forks anyway).
3. **Local `assembleRelease` without env vars falls back to the debug key** (release
   variant, debug signature) so R8 builds work out of the box; CI sets the env vars and
   signs with the real keystore.
4. **Agent creates the keystore and secrets**: keytool-generated keystore outside the repo,
   uploaded with `gh secret set`; user backs up keystore + passwords.

## Approach

### 1. Keystore + GitHub secrets

Generate once, stored **outside the repo** (`~/keystores/quire-release.keystore`, RSA 4096,
validity ~30 years = 10950 days, alias `quire`, one random password printed once for the
user's backup, used as both store and key password):

```bash
keytool -genkeypair -v -keystore ~/keystores/quire-release.keystore -alias quire \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Quire Reader"
base64 -w0 ~/keystores/quire-release.keystore  # → secret RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_KEYSTORE_BASE64  --repo sekhnat/Quire < quire-release.b64
gh secret set RELEASE_KEYSTORE_PASSWORD --repo sekhnat/Quire   # = $PASS
gh secret set RELEASE_KEY_ALIAS         --repo sekhnat/Quire   # = quire
gh secret set RELEASE_KEY_PASSWORD      --repo sekhnat/Quire   # = $PASS
```

### 2. `app/build.gradle.kts` — signing, versioning, R8

- `defaultConfig`: read `-PversionCode` / `-PversionName` Gradle properties
  (`providers.gradleProperty(...).getOrElse(...)`) with `1` / `"1.0"` fallbacks.
- New `signingConfigs.create("release")` reading env vars via providers:
  `QUIRE_KEYSTORE_FILE`, `QUIRE_KEYSTORE_PASSWORD`, `QUIRE_KEY_ALIAS`, `QUIRE_KEY_PASSWORD`.
  - all four set → real release signing;
  - none set → release buildType uses the standard `debug` signing config (local fallback);
  - partially set → throw with a clear message listing the missing variables.
- `buildTypes.release`: `isMinifyEnabled = true`, keep existing `proguardFiles(...)`
  reference (the file it points at now exists), `signingConfig` per the rules above.

### 3. New `app/proguard-rules.pro`

```
# R8 release rules for Quire.

# Keep stack traces retraceable via mapping.txt (uploaded as a CI artifact).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- WebView JavaScript bridge (vendored Readium navigator). ---
# JS calls these methods by name through addJavascriptInterface(), which R8 cannot trace.
# proguard-android-optimize.txt carries the same global rule; scoped here so the bridge
# survives even if the default file is ever swapped out.
-keepclassmembers class com.quire.reader.navigator.** {
    @android.webkit.JavascriptInterface <methods>;
}

# --- kotlinx-serialization (official rule set, scoped to app code). ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.quire.reader.**$$serializer { *; }
-keepclassmembers class com.quire.reader.** {
    *** Companion;
}
-keepclasseswithmembers class com.quire.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

Then build once and consume `app/build/outputs/mapping/release/missing_rules.txt` — add
`-dontwarn` lines for classes R8 flags from optional dependencies (expected: a few Readium
/ kotlinx reflection-only paths). Keep rules already covered by bundled library rules
(Coil, Room, DataStore, kotlinx-serialization-core) are not duplicated.

### 4. `.github/workflows/build.yml`

- Build job keeps `./gradlew assembleDebug test lint` for all events (tests/lint stay on
  the debug variant).
- New step for push tags, push main, and workflow_dispatch (`if: github.event_name != 'pull_request'`):
  1. Decode `RELEASE_KEYSTORE_BASE64` to `$RUNNER_TEMP/quire-release.keystore`.
  2. Export the `QUIRE_*` env vars from the secrets.
  3. Derive version: tag → `VERSION_NAME="${GITHUB_REF_NAME#v}"`, else `dev-${GITHUB_SHA::7}`.
  4. `./gradlew assembleRelease -PversionCode="$GITHUB_RUN_NUMBER" -PversionName="$VERSION_NAME"`.
  5. Copy `app-release.apk` to `dist/quire-$VERSION_NAME.apk`
     (→ `quire-dev-a1b2c3d.apk` / `quire-1.2.3.apk`).
- Artifacts on push events: `quire-release-apk` (the APK) and `r8-mapping`
  (`app/build/outputs/mapping/release/mapping.txt`); the existing `quire-debug-apk`
  artifact becomes PR-only; `reports` unchanged. PR runs otherwise identical to today.
- Release job (`v*` tags): download `quire-release-apk` and `r8-mapping`, create the
  GitHub release attaching `dist/*.apk` **and** `mapping.txt` (permanent de-obfuscation
  record — CI artifacts expire; releases don't).

### 5. Docs — `README.md` only

- **Install**: pushes now build a release-signed APK (artifact `quire-release-apk`);
  installing over any previous release build upgrades in place and keeps the library;
  tagged releases attach the same APK. Note the one-time uninstall for anyone upgrading
  from a debug build.
- **Build from source**: add `./gradlew assembleRelease` — R8 enabled, locally signed
  with the debug key unless the `QUIRE_*` env vars are set; APK path
  `app/build/outputs/apk/release/app-release.apk`.
- ANDROID.md is a generic environment guide — no changes needed there.

## Files to modify

- `app/build.gradle.kts` — signing config, version Gradle properties, R8 toggle.
- `app/proguard-rules.pro` — new file (keep rules).
- `.github/workflows/build.yml` — release build + signing + version derivation + artifacts.
- `README.md` — Install and Build-from-source sections.
- Outside repo: `~/keystores/quire-release.keystore` + 4 GitHub secrets.

## Steps

- [x] 1. Generate release keystore with keytool; set the 4 secrets via `gh secret set`; print password once for user backup.
- [x] 2. `app/build.gradle.kts`: versionCode/versionName from `-P` properties; `signingConfigs` release-from-env with debug fallback + partial-env error; release buildType: `isMinifyEnabled = true` + signingConfig.
- [x] 3. Create `app/proguard-rules.pro` with the rules above.
- [x] 4. `./gradlew assembleRelease` locally; resolve `missing_rules.txt` warnings with targeted `-dontwarn`/keep additions until the build is clean.
- [x] 5. Rewrite `.github/workflows/build.yml` build job (release step + artifacts) and release job (release APK + mapping.txt).
- [x] 6. Update `README.md` Install + Build-from-source sections.
- [ ] 7. Local verification on the emulator (below), then push and verify CI end-to-end.

## Verification

Local (JDK 21 via `zsh -fc 'source ~/.config/android/env.zsh; ...'`):

- `./gradlew assembleDebug test lint` still green; `./gradlew assembleRelease` succeeds
  and R8 reports no missing rules.
- `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` shows
  the debug-key fallback locally.
- Upgrade path: `adb install -r` of a build with `-PversionCode=1001` over `-PversionCode=1000`
  keeps the library (scan a book, mark progress, reinstall, confirm both survive).
- Smoke-test the R8'd release on the emulator: open an EPUB (taps, scroll, paged flips =
  JS bridges), switch reader prefs + theme (serialization), force a rescan (Room,
  DataStore, Calibre parser, Coil covers).

CI (after push):

- Two consecutive runs on main → download both `quire-release-apk` artifacts →
  `apksigner verify --print-certs` shows identical SHA-256 cert digests →
  `adb install -r` newer over older succeeds; `dumpsys package com.quire.reader`
  shows versionCode = run number.
- Push a `v*` tag → GitHub release created with `quire-<ver>.apk` + `mapping.txt`;
  installs over the previous release build without data loss.