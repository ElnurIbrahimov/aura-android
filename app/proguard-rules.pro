# Aura proguard rules
-keep class com.aura.** { *; }
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
-keep class kotlinx.coroutines.** { *; }
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
