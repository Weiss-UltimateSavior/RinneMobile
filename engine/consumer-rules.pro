# Preserve JNI/SDL engine entry points used by embedded native engines.
-keep class org.tvp.kirikiri2.** { *; }
# krkrsdl3 links against SDL3.  SDL's JNI_OnLoad resolves these classes and
# callbacks by their literal names, which R8 cannot infer from native code.
# Keep the full surface: native setup is only one of many callbacks looked up
# dynamically (including onNativeSoftReturnKey).
-keep class org.tvp.krkrsdl3.** { *; }
-keep class org.libsdl3.app.** { *; }
-keep class com.yuri.onscripter.** { *; }
-keep class org.libsdl.app.** { *; }
-keep class org.cocos2dx.lib.** { *; }
-keep class bridge.NativeBridge { *; }
-keep class com.akira.tyranoemu.remote.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
