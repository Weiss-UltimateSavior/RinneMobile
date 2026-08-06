# App-specific ProGuard rules.
# Engine keep rules are in engine/consumer-rules.pro and applied automatically.

# Keep IJKPlayer Java classes referenced by libijksdl.so via native FindClass.
# R8 无法识别 native 层通过字符串 FindClass 引用的类，会把它们当无用代码剥离，
# 导致 libijksdl.so JNI_OnLoad 因找不到 misc/IMediaDataSource、misc/IAndroidIO 而返回
# JNI_ERR，进程抛 UnsatisfiedLinkError 崩溃。须整体 keep 以防回归。
-keep class tv.danmaku.ijk.media.player.** { *; }

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
