# Telegram Proxy Client
-keep class com.telegramproxy.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
