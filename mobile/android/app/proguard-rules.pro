# NaviSense ProGuard Rules
# ============================================================

# Keep Retrofit interfaces
-keep,allowobfuscation interface com.navisense.core.NaviSenseApi

# Keep data classes used by Gson
-keep class com.navisense.core.PositionResponse { *; }
-keep class com.navisense.core.Landmark { *; }
-keep class com.navisense.model.MarkerItem { *; }

# Keep Google Maps SDK
-keep class com.google.android.gms.maps.** { *; }

# Keep OkHttp logging
-dontwarn okhttp3.logging.**
-keep class okhttp3.** { *; }

# General AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
