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

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug key locally, release keystore in CI — keeps every artifact upgradeable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by Readium (java.time etc. on older API levels).
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
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
