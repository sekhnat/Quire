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