# App-specific ProGuard rules.
# Engine keep rules are in engine/consumer-rules.pro and applied automatically.

# Keep model classes used in JSON serialization
-keep class com.core.model.** { *; }

# Keep Retrofit API service interface
-keep class com.core.net.ApiService { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep data classes used with SQL
-keep class com.core.data.** { *; }

# Preserve line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
