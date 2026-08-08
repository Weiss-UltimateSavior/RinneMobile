package org.tvp.krkrsdl3;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.View;

import org.libsdl3.app.SDLActivity;

import java.util.ArrayList;
import java.util.Objects;

public class KRKRActivity extends SDLActivity {
    /** 引擎 argv 协议键：启动器经该 extra 传入游戏启动参数列表（首项为启动文件绝对路径）。 */
    public static final String SHAREDPREF_GAMECONFIG = "gameargs";
    private ArrayList<String> m_gameargs;

    // override sdl functions
    static {
        System.loadLibrary("SDL3");
        System.loadLibrary("krkrsdl3");
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
                "SDL3",
                "krkrsdl3"
        };
    }

    @Override
    protected String[] getArguments() {
        // 兼容两种 extra 形态：启动器传 StringArrayList（getStringArrayListExtra），
        // adb 调试传 String[]（--esa → getStringArrayExtra）。两者皆缺省时给空参数，
        // 由引擎 TVPTryStartupFromArchives 自行定位 startup.tjs。
        if (m_gameargs != null)
            return m_gameargs.toArray(new String[0]);
        String[] arr = getIntent().getStringArrayExtra(SHAREDPREF_GAMECONFIG);
        if (arr != null)
            return arr;
        return new String[] { "" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setNativeAssetManager(getAssets());
        Intent intent = getIntent();

        m_gameargs = intent.getStringArrayListExtra(SHAREDPREF_GAMECONFIG);
        this.fullscreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.fullscreen();
    }

    @Override
    protected void onDestroy() {
        // 输入弹窗未确认时销毁宿主：解除 native 线程 WaitInputResult 阻塞，
        // 防止 SDL3 onDestroy 的 mSDLThread.join 死锁/线程泄漏。
        KRKRCall.cancelPendingInput();
        super.onDestroy();
    }

    public void onWindowFocusChanged (boolean hasFocus) {
        if(hasFocus) this.fullscreen();
    }

    /** 全屏沉浸入口：默认隐藏系统栏并强制横屏；Rinne 集成子类可覆盖以定制沉浸式布局。 */
    protected void fullscreen() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN ;
        decorView.setSystemUiVisibility(uiOptions);
        try {
            Objects.requireNonNull(this.getSupportActionBar()).hide();
        }
        catch (NullPointerException ignored){}
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    public native void setNativeAssetManager(AssetManager assetManager);
}
