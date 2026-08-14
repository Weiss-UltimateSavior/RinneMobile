package com.akira.tyranoemu.remote;

import com.core.nativeplugin.NativePluginConstants;
import com.core.nativeplugin.NativePluginManager;

public final class ArtemisActivityV3 extends ArtemisLauncherBaseActivity {
 @Override public void loadEngineLibrary() {
   // 见 ArtemisActivityV1：bootstrap loader 负责引擎主体入口，此处 System.load 登记 JNI 符号。
   String lib = NativePluginManager.artemisLibPath(this, NativePluginConstants.LIB_ARTEMIS_COMPATIBLE_V2);
   if (lib == null) throw new IllegalStateException("Artemis 外置插件未就绪，请重新导入插件");
   System.load(lib);
 }
}