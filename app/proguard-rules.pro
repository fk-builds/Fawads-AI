# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
# Optional OkHttp integrations that simply aren't on the classpath.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.net.**

# JSON
-keep class org.json.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.**

# AndroidX security / EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# Keep our model + command classes
-keep class com.fawads.ai.model.** { *; }
-keep class com.fawads.ai.ai.AppCommand { *; }
-keep class com.fawads.ai.util.UpdateManager$Update { *; }

# Gemini Live websocket payload is built with reflection-free org.json — keep
# the client's inner callback signatures intact for R8 full mode.
-keep class com.fawads.ai.ai.GeminiLiveClient { *; }
-keep class com.fawads.ai.ai.GeminiLiveClient$* { *; }

# ML Kit / CameraX ship their own rules, but R8 full mode is picky.
-dontwarn com.google.mlkit.**
-dontwarn androidx.camera.**

# Retrofit/OkHttp logging (if used)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
