# ==============================================================
# ProGuard Rules untuk Chili Disease Detector
# ==============================================================

# ── TensorFlow Lite ──
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.**

# ── CameraX ──
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Detector & Model classes ──
-keep class com.example.chilidisease.detector.** { *; }
-keep class com.example.chilidisease.utils.** { *; }

# ── Kotlin Coroutines ──
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Aturan umum Android ──
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# ── Jangan obfuscate nama model TFLite ──
-keepclassmembers class * {
    @org.tensorflow.lite.annotations.UsedByReflection *;
}
