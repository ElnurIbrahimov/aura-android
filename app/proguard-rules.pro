# Aura proguard rules
#
# Philosophy: no blanket keeps. R8 sees direct references (Hilt/Room/WorkManager
# codegen, Compose) on its own; only genuinely reflective entry points get
# narrow, documented keep rules.

# --- kotlinx.serialization (targeted; @Serializable classes in com.aura) ---
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.aura.**$$serializer { *; }
-keepclassmembers class com.aura.** {
    *** Companion;
}
-keepclasseswithmembers class com.aura.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- JavaMail (com.sun.mail:android-mail / android-activation) ---
# Session.getTransport("smtp") resolves the transport implementation from the
# META-INF/javamail.default.providers resource and instantiates it reflectively.
-keep class com.sun.mail.smtp.** { *; }
# MIME DataContentHandlers (text/plain, message/rfc822, ...) are resolved from
# mailcap resource files and instantiated reflectively by javax.activation.
-keep class com.sun.mail.handlers.** { *; }
# javax.activation's command map / data handler machinery is resource-driven;
# its classes are looked up by name at runtime.
-keep class javax.activation.** { *; }
-dontwarn java.awt.**

# --- OkHttp optional security providers (never on Android) ---
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Android desugaring rewrites Java 9 string-concatenation call sites; R8 must
# not require the JVM-only bootstrap implementation in the final APK.
-dontwarn java.lang.invoke.StringConcatFactory
# Annotation-processing APIs arrive through transitive compile-time tooling;
# they are not Android runtime dependencies.
-dontwarn javax.lang.model.**
# pdfbox-android's JPXFilter references the JPEG-2000 decoder from the optional
# `jp2-android` artifact, which we do not depend on. Without this, R8 fails the
# whole release build on the missing class. Documents containing JPX-encoded
# images will fail to decode that image — text extraction, which is all we use
# PDFBox for, is unaffected.
-dontwarn com.gemalto.jp2.**
