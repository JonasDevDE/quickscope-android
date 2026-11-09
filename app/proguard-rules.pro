# Modelle und API-Schnittstelle (kotlinx.serialization, Retrofit)
-keep class com.jonasschnell.quickscope.data.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, RuntimeVisibleAnnotations, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }

# Retrofit mit Coroutinen
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Hintergrunddienst (Media3) wird ueber das Manifest gefunden
-keep class com.jonasschnell.quickscope.audio.AudiobookService { *; }

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
