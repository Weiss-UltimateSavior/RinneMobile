package org.cocos2dx.lib;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ViewGroup;
import java.util.Arrays;
import java.util.Iterator;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

public abstract class Cocos2dxActivity extends Activity implements Cocos2dxHelper.Cocos2dxHelperListener {
    private static final String TAG = "Cocos2dxActivity";
    private static Cocos2dxActivity sContext;

    private Cocos2dxGLSurfaceView mGLSurfaceView = null;
    public int[] mGLContextAttrs = null;
    private Cocos2dxHandler mHandler = null;
    private Cocos2dxVideoHelper mVideoHelper = null;
    private Cocos2dxWebViewHelper mWebViewHelper = null;
    private Cocos2dxEditBoxHelper mEditBoxHelper = null;
    private boolean hasFocus = false;
    public ResizeLayout mFrameLayout = null;

    public static Context getContext() {
        return sContext;
    }

    private static native int[] getGLContextAttrs();

    private static boolean isAndroidEmulator() {
        String model = Build.MODEL;
        Log.d(TAG, "model=" + model);
        String product = Build.PRODUCT;
        Log.d(TAG, "product=" + product);
        boolean emulator = product != null && (product.equals("sdk") || product.contains("_sdk") || product.contains("sdk_"));
        Log.d(TAG, "isEmulator=" + emulator);
        return emulator;
    }

    private void resumeIfHasFocus() {
        if (this.hasFocus) {
            Cocos2dxHelper.onResume();
            if (this.mGLSurfaceView != null) this.mGLSurfaceView.onResume();
        }
    }

    public Cocos2dxGLSurfaceView getGLSurfaceView() {
        return this.mGLSurfaceView;
    }

    public void init() {
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(-1, -1);
        ResizeLayout resizeLayout = new ResizeLayout(this);
        this.mFrameLayout = resizeLayout;
        resizeLayout.setLayoutParams(layoutParams);

        ViewGroup.LayoutParams editParams = new ViewGroup.LayoutParams(-1, -2);
        Cocos2dxEditBox editBox = new Cocos2dxEditBox(this);
        editBox.setLayoutParams(editParams);
        this.mFrameLayout.addView(editBox);

        Cocos2dxGLSurfaceView glSurfaceView = onCreateView();
        this.mGLSurfaceView = glSurfaceView;
        this.mFrameLayout.addView(glSurfaceView);

        if (isAndroidEmulator()) {
            this.mGLSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        }

        this.mGLSurfaceView.setCocos2dxRenderer(new Cocos2dxRenderer());
        this.mGLSurfaceView.setCocos2dxEditText(editBox);
        setContentView(this.mFrameLayout);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Iterator<PreferenceManager.OnActivityResultListener> it = Cocos2dxHelper.getOnActivityResultListeners().iterator();
        while (it.hasNext()) {
            it.next().onActivityResult(requestCode, resultCode, data);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        onLoadNativeLibraries();
        sContext = this;
        this.mHandler = new Cocos2dxHandler(this);
        Cocos2dxHelper.init(this);
        this.mGLContextAttrs = getGLContextAttrs();
        init();
        if (this.mVideoHelper == null) this.mVideoHelper = new Cocos2dxVideoHelper(this, this.mFrameLayout);
        if (this.mWebViewHelper == null) this.mWebViewHelper = new Cocos2dxWebViewHelper(this.mFrameLayout);
        if (this.mEditBoxHelper == null) this.mEditBoxHelper = new Cocos2dxEditBoxHelper(this.mFrameLayout);
        getWindow().setSoftInputMode(32);
    }

    public Cocos2dxGLSurfaceView onCreateView() {
        Cocos2dxGLSurfaceView glSurfaceView = new Cocos2dxGLSurfaceView(this);
        if (this.mGLContextAttrs != null && this.mGLContextAttrs.length > 3 && this.mGLContextAttrs[3] > 0) {
            glSurfaceView.getHolder().setFormat(-3);
        }
        if (this.mGLContextAttrs != null) {
            glSurfaceView.setEGLConfigChooser(new Cocos2dxEGLConfigChooser(this, this.mGLContextAttrs));
        }
        return glSurfaceView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void onLoadNativeLibraries() {
        try {
            System.loadLibrary(getPackageManager().getApplicationInfo(getPackageName(), 128).metaData.getString("android.app.lib_name"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
        Cocos2dxHelper.onPause();
        if (this.mGLSurfaceView != null) this.mGLSurfaceView.onPause();
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();
        resumeIfHasFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        Log.d(TAG, "onWindowFocusChanged() hasFocus=" + hasFocus);
        super.onWindowFocusChanged(hasFocus);
        this.hasFocus = hasFocus;
        resumeIfHasFocus();
    }

    @Override
    public void runOnGLThread(Runnable runnable) {
        if (this.mGLSurfaceView != null) this.mGLSurfaceView.queueEvent(runnable);
    }

    public void setKeepScreenOn(final boolean keepScreenOn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Cocos2dxActivity.this.mGLSurfaceView != null) {
                    Cocos2dxActivity.this.mGLSurfaceView.setKeepScreenOn(keepScreenOn);
                }
            }
        });
    }

    @Override
    public void showDialog(String title, String message) {
        Message msg = new Message();
        msg.what = Cocos2dxHandler.HANDLER_SHOW_DIALOG;
        msg.obj = new Cocos2dxHandler.DialogMessage(title, message);
        this.mHandler.sendMessage(msg);
    }

    public class Cocos2dxEGLConfigChooser implements GLSurfaceView.EGLConfigChooser {
        protected int[] configAttribs;

        public class ConfigValue implements Comparable<ConfigValue> {
            public EGLConfig config;
            public int[] configAttribs;
            public int value;

            public ConfigValue(int[] attrs) {
                this.config = null;
                this.value = 0;
                this.configAttribs = attrs;
                calcValue();
            }

            public ConfigValue(EGL10 egl, EGLDisplay display, EGLConfig config) {
                this.value = 0;
                this.config = config;
                this.configAttribs = new int[6];
                this.configAttribs[0] = findConfigAttrib(egl, display, config, 12324, 0);
                this.configAttribs[1] = findConfigAttrib(egl, display, config, 12323, 0);
                this.configAttribs[2] = findConfigAttrib(egl, display, config, 12322, 0);
                this.configAttribs[3] = findConfigAttrib(egl, display, config, 12321, 0);
                this.configAttribs[4] = findConfigAttrib(egl, display, config, 12325, 0);
                this.configAttribs[5] = findConfigAttrib(egl, display, config, 12326, 0);
                calcValue();
            }

            private void calcValue() {
                int depth = this.configAttribs[4];
                if (depth > 0) this.value = this.value + 536870912 + ((depth % 64) << 6);
                int stencil = this.configAttribs[5];
                if (stencil > 0) this.value = (stencil % 64) + this.value + 268435456;
                int alpha = this.configAttribs[3];
                if (alpha > 0) this.value = this.value + 1073741824 + ((alpha % 16) << 24);
                int green = this.configAttribs[1];
                if (green > 0) this.value += (green % 16) << 20;
                int blue = this.configAttribs[2];
                if (blue > 0) this.value += (blue % 16) << 16;
                int red = this.configAttribs[0];
                if (red > 0) this.value += (red % 16) << 12;
            }

            @Override
            public String toString() {
                return "{ color: " + this.configAttribs[3] + this.configAttribs[2] + this.configAttribs[1] + this.configAttribs[0]
                        + "; depth: " + this.configAttribs[4] + "; stencil: " + this.configAttribs[5] + ";}";
            }

            @Override
            public int compareTo(ConfigValue other) {
                if (this.value < other.value) return -1;
                return this.value > other.value ? 1 : 0;
            }
        }

        public Cocos2dxEGLConfigChooser(Cocos2dxActivity activity, int red, int green, int blue, int alpha, int depth, int stencil) {
            this.configAttribs = new int[]{red, green, blue, alpha, depth, stencil};
        }

        public Cocos2dxEGLConfigChooser(Cocos2dxActivity activity, int[] attrs) {
            this.configAttribs = attrs;
        }

        public Cocos2dxEGLConfigChooser(int[] attrs) {
            this.configAttribs = attrs;
        }

        private int findConfigAttrib(EGL10 egl, EGLDisplay display, EGLConfig config, int attribute, int defaultValue) {
            int[] value = new int[1];
            return egl.eglGetConfigAttrib(display, config, attribute, value) ? value[0] : defaultValue;
        }

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[] attrs = this.configAttribs;
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (attrs != null && attrs.length >= 6
                    && egl.eglChooseConfig(display, new int[]{12324, attrs[0], 12323, attrs[1], 12322, attrs[2], 12321, attrs[3], 12325, attrs[4], 12326, attrs[5], 12352, 4, 12344}, configs, 1, num)
                    && num[0] > 0) {
                return configs[0];
            }
            int[] es2 = {12352, 4, 12344};
            if (!egl.eglChooseConfig(display, es2, null, 0, num) || num[0] <= 0) {
                Log.e("device_policy", "Can not select an EGLConfig for rendering.");
                return null;
            }
            int count = num[0];
            ConfigValue[] values = new ConfigValue[count];
            EGLConfig[] allConfigs = new EGLConfig[count];
            egl.eglChooseConfig(display, es2, allConfigs, count, num);
            for (int i = 0; i < count; i++) values[i] = new ConfigValue(egl, display, allConfigs[i]);
            Arrays.sort(values);
            ConfigValue desired = new ConfigValue(attrs != null && attrs.length >= 6 ? attrs : new int[]{8, 8, 8, 8, 16, 0});
            int index = 0;
            while (index < count - 1 && desired.compareTo(values[index]) >= 0) index++;
            Log.w("cocos2d", "Can't find EGLConfig match: " + desired + ", instead of closest one:" + values[index]);
            return values[index].config;
        }
    }
}