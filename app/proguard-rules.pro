# R8/ProGuard ルール for FLACtify

# Media3 関連のクラスを保持
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# jaudiotagger を保持
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-keep class coil.** { *; }

# JSON パース
-keep class org.json.** { *; }

# Keep ViewModel
-keep class com.flactify.viewmodel.** { *; }

# Keep Service
-keep class com.flactify.PlaybackService { *; }
