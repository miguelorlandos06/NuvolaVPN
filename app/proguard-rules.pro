-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class com.nuvola.vpn.engine.** { *; }
-keep class com.nuvola.vpn.service.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class androidx.compose.** { *; }
