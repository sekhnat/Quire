// The `.qdict` dictionary format: a pure Kotlin/JVM library, so the app reads the dictionary with exactly the code the
// build-time converter verifies it with.
plugins {
  alias(libs.plugins.kotlin.jvm)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  jvmToolchain(21)
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
  testImplementation(libs.junit)
}
