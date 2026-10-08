import com.android.build.api.artifact.SingleArtifact
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
    namespace = "com.quire.reader"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.quire.reader"
        minSdk = 30
        targetSdk = 36
        // CI passes -PversionCode/-PversionName (e.g. the GitHub run number and tag);
        // local builds fall back to these defaults.
        versionCode = providers.gradleProperty("versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("versionName").getOrElse("1.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The bundled SQLite (search index) is native code; 64-bit only keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // Scroll-window telemetry (renderer memory, live documents, blank viewport time) is
        // compiled in only for the benchmark build type below.
        buildConfigField("boolean", "SCROLL_TELEMETRY", "false")
    }

    signingConfigs {
        val signingEnv = mapOf(
            "QUIRE_KEYSTORE_FILE" to providers.environmentVariable("QUIRE_KEYSTORE_FILE"),
            "QUIRE_KEYSTORE_PASSWORD" to providers.environmentVariable("QUIRE_KEYSTORE_PASSWORD"),
            "QUIRE_KEY_ALIAS" to providers.environmentVariable("QUIRE_KEY_ALIAS"),
            "QUIRE_KEY_PASSWORD" to providers.environmentVariable("QUIRE_KEY_PASSWORD"),
        )
        val missing = signingEnv.filterValues { !it.isPresent }.keys
        when {
            missing.size == signingEnv.size ->
                Unit // No signing env vars (local build): release falls back to the debug key below.
            missing.isNotEmpty() ->
                throw GradleException(
                    "Incomplete release signing configuration, missing: ${missing.joinToString()}. " +
                        "Set all four QUIRE_* variables (as CI does) or none of them " +
                        "(local builds are then signed with the debug key).",
                )
            else ->
                create("release") {
                    storeFile = file(signingEnv.getValue("QUIRE_KEYSTORE_FILE").get())
                    storePassword = signingEnv.getValue("QUIRE_KEYSTORE_PASSWORD").get()
                    keyAlias = signingEnv.getValue("QUIRE_KEY_ALIAS").get()
                    keyPassword = signingEnv.getValue("QUIRE_KEY_PASSWORD").get()
                }
        }
    }

    // Readium's preference types parse their default colours with android.graphics.Color in
    // static initializers; unit tests run without Android, so unmocked framework calls
    // return defaults instead of throwing.
    testOptions {
      unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug key locally, release keystore in CI — keeps every artifact upgradeable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Release-based build for measuring the scroll surface: its own application id (so a
        // run never replaces the installed app or a .dbtest install), telemetry on. Not
        // minified: what it measures is the WebView renderer, and the benchmark reaches
        // navigator internals. Debuggable because AGP 9.0.1 runs L8 over the test APK of a
        // non-debuggable app and rejects the androidTest names that contain spaces; the
        // renderer process, where the measured work happens, is not affected. Run its
        // instrumented tests with -PtestBuildType=benchmark.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
            matchingFallbacks += "release"
            buildConfigField("boolean", "SCROLL_TELEMETRY", "true")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by Readium (java.time etc. on older API levels).
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}


// Trust boundary: Quire reads books from shared storage with no network access, so the
// reader WebViews cannot exfiltrate anything. That backstop holds only as long as the app
// never gains the INTERNET permission — e.g. through a new dependency whose manifest carries
// it. Fail every build whose merged manifest does.

abstract class VerifyNoInternetPermissionTask : DefaultTask() {

    /**
     * Wired from AGP's artifact API rather than a hand-built path: the provider carries
     * the producing task (process<Variant>Manifest) as a dependency, so the merged
     * manifest is guaranteed to exist — and be current — whenever this task runs. A
     * finalizedBy hook would only order against one task and races the rest of the
     * manifest chain on a clean build directory (CI fails, stale files mask it locally).
     */
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val granted = mergedManifest.get().asFile.readLines().any { "android.permission.INTERNET" in it }
        if (granted) {
            throw GradleException(
                "The merged manifest grants android.permission.INTERNET. " +
                    "Quire must stay offline: the reader renders untrusted EPUB content and " +
                    "relies on having no network permission. Find the dependency adding it " +
                    "(./gradlew :app:dependencies) and remove or exclude it."
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register("verifyNoInternetPermission$capitalized", VerifyNoInternetPermissionTask::class) {
            group = "verification"
            description = "Asserts the merged ${variant.name} manifest does not gain android.permission.INTERNET."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        // The guard runs on every APK build of the variant and on every `check` run; the
        // provider above pulls the manifest-producing task into those graphs.
        tasks.matching { it.name == "assemble$capitalized" }.configureEach { dependsOn(verify) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)

  // EPUB parsing and rendering
  implementation(libs.readium.shared)
  implementation(libs.readium.streamer)
  implementation(libs.readium.navigator)
  implementation(libs.androidx.fragment.ktx)
  // Compile-visible versions of runtime-scope transitive deps used by the vendored
  // Readium navigator sources (com.quire.reader.navigator).
  implementation(libs.timber)
  implementation(libs.jsoup)
  implementation(libs.androidx.webkit)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.viewpager)

  // Persistence, background work, images
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.sqlite.bundled)
  testImplementation(libs.androidx.room.testing)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.work.runtime)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.serialization.json)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
