# kotlinx.serialization: keep generated serializers for the shared API models and the watch's own.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.st000ps.powerstone.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.st000ps.powerstone.**$$serializer { *; }

# OkHttp's optional platform integrations.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
