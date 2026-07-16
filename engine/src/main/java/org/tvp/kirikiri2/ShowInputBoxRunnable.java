package org.tvp.kirikiri2;

import org.cocos2dx.lib.Cocos2dxGLSurfaceView;

public final class ShowInputBoxRunnable implements Runnable {
    public final String inputText;
    public ShowInputBoxRunnable(String inputText) { this.inputText = inputText; }
    @Override public void run() {
        // 暂停 GL 线程，防止引擎在弹窗未确认时继续渲染
        Cocos2dxGLSurfaceView gl = KR2Activity.sInstance != null ? KR2Activity.sInstance.getGLSurfaceView() : null;
        if (gl != null) {
            try { gl.onPause(); } catch (Throwable ignored) {}
        }
        KrDialogModel dm = KR2Activity.mDialogMessage;
        KR2Activity.mCurrentDialog = KrDialogStyle.showInputBox(KR2Activity.sInstance, dm.title, dm.message, inputText, dm.buttons,
                (which, text) -> {
                    KR2Activity.mCurrentDialog = null;
                    // 恢复 GL 线程
                    if (gl != null) {
                        try { gl.onResume(); } catch (Throwable ignored) {}
                    }
                    KR2Activity.notifyDialogResult(which, text);
                });
    }
}
