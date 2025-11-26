# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TensorFlow Lite - Keep model classes and native methods
-keep class org.tensorflow.lite.** { *; }
-keep class com.example.expressora.recognition.tflite.** { *; }
-keep class com.example.expressora.recognition.mediapipe.** { *; }

# Keep TFLite model assets
-keepclassmembers class * {
    @org.tensorflow.lite.support.common.FileUtil *;
}

# MediaPipe
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Compose - keep ViewModel and StateFlow
-keep class androidx.lifecycle.** { *; }
-keep class androidx.compose.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep recognition engine classes
-keep class com.example.expressora.recognition.** { *; }
-keepclassmembers class com.example.expressora.recognition.** {
    *;
}

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep BuildConfig for debug checks
-keepclassmembers class com.example.expressora.BuildConfig {
    public static final boolean DEBUG;
}

# Prevent obfuscation of native method names for JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep custom views
-keep class com.example.expressora.recognition.view.** { *; }
-keepclassmembers class com.example.expressora.recognition.view.** {
    public <init>(...);
}

# Exclude test classes from release builds (fixes R8 minification errors)
-dontwarn org.robolectric.**
-keep class org.robolectric.** { *; }
-dontwarn android.app.ActivityThread

# Exclude all test classes
-keep class * extends junit.framework.TestCase { *; }
-dontwarn junit.framework.**
-dontwarn org.junit.**
-dontwarn org.hamcrest.**