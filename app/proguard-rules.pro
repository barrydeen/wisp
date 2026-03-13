# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# secp256k1-kmp JNI
-keep class fr.acinq.secp256k1.** { *; }

# Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp — keep class names so crash stack traces are readable
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coil
-dontwarn coil3.**

# AndroidX Security (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }

# ExoPlayer / Media3
-dontwarn androidx.media3.**

# ZXing
-keep class com.google.zxing.** { *; }

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# kmp-tor
-keep class io.matthewnelson.kmp.tor.** { *; }
-dontwarn io.matthewnelson.kmp.tor.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ObjectBox
-keep class io.objectbox.** { *; }
-dontwarn io.objectbox.**
-keep class com.wisp.app.db.** { *; }

# Breez SDK Spark (UniFFI bindings)
-keep class breez_sdk_spark.** { *; }
-dontwarn breez_sdk_spark.**
# JNA (used by Breez SDK UniFFI)
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# java.lang.management (not available on Android)
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
