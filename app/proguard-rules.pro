# R8 开启后这里必须给反射/序列化留出活口，否则 release 包会在运行时崩、
# 而 debug 包一切正常——这类问题最难查。

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.local.autobookkeeping.** {
    *** Companion;
    @kotlinx.serialization.SerialName <fields>;
}
-keepclasseswithmembers class com.local.autobookkeeping.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.local.autobookkeeping.**$$serializer { *; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- ML Kit 文字识别 ---
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
