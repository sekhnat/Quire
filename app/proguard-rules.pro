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
# --- Readium publication services (org.readium.r2). ---
# Publication.ServicesBuilder keys its service-factory map by Class.getSimpleName()
# and looks services up with findService(CoverService::class)-style instance checks.
# Under minification R8 renamed ResourceCoverService and StringSearchService both to
# simple name "k", so the cover fallback's containsKey("k") saw the EPUB parser's search
# factory and the cover service was never registered - covers vanished silently in
# release builds. Keeping Readium's original names keeps those keys distinct and the
# service interfaces alive. Do not remove.
-keep class org.readium.r2.** { *; }
-keep interface org.readium.r2.** { *; }