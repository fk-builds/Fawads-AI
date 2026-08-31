# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# JSON
-keep class org.json.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-dontwarn kotlinx.coroutines.**

# AndroidX security / EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# Keep our model + command classes
-keep class com.fawads.ai.model.** { *; }
-keep class com.fawads.ai.ai.AppCommand { *; }

# Retrofit/OkHttp logging (if used)
-keepattributes Signature
-keepattributes *Annotation*
