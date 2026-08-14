package com.akira.tyranoemu.remote;

import com.core.nativeplugin.NativePluginConstants;
import com.core.nativeplugin.NativePluginManager;

public final class ArtemisActivityV1 extends ArtemisLauncherBaseActivity {
 @Override public void loadEngineLibrary() {
   // bootstrap loader 在 super.onCreate() 阶段已 dlopen 真实库并转发 ANativeActivity_onCreate
   // 启动引擎主体；此处再 System.load 同一 so，使 ART 将其实native 方法（OnFinishVideo 等）
   // 登记到 native library 表，否则 Java native 调用会 UnsatisfiedLinkError。
   String lib = NativePluginManager.artemisLibPath(this, NativePluginConstants.LIB_ARTEMIS);
   if (lib == null) throw new IllegalStateException("Artemis 外置插件未就绪，请重新导入插件");
   System.load(lib);
 }
}