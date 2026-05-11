# ReaderToMeAI ProGuard Rules

# Keep sherpa-onnx JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep epublib
-keep class nl.siegmann.epublib.** { *; }

# Keep Jsoup
-keep class org.jsoup.** { *; }

# Compose
-dontwarn androidx.compose.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
