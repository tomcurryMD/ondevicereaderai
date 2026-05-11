# OnDeviceReaderAI ProGuard/R8 Rules

# Keep sherpa-onnx JNI — native methods must not be stripped
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Keep epublib
-keep class nl.siegmann.epublib.** { *; }
-dontwarn nl.siegmann.epublib.**

# Keep Jsoup
-keep class org.jsoup.** { *; }

# PdfBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.fontbox.**

# Commons Compress
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# SLF4J
-dontwarn org.slf4j.**

# Suppress R8 warnings for XML parser classes excluded from the build
-dontwarn org.xmlpull.**
-dontwarn org.kxml2.**
-dontwarn javax.xml.**

# Compose
-dontwarn androidx.compose.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# DataStore
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Keep WebView JS interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
