# kotlinx.serialization: keep generated serializers for the wire and storage formats.
-keepclassmembers class com.prempoddar.librechat.** { *** Companion; }
-keepclasseswithmembers class com.prempoddar.librechat.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.prempoddar.librechat.**$$serializer { *; }
# SQLCipher loads classes from native code.
-keep class net.zetetic.database.** { *; }
-dontwarn javax.naming.**
