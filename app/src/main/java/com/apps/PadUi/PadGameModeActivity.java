package com.apps.PadUi;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.apps.LauncherActivity;
import com.apps.LauncherIntents;
import com.apps.theme.LauncherTheme;
import com.apps.theme.LauncherToneSwitcher;
import com.core.R;
import com.core.databinding.ActivityPadGameModeBinding;
import com.core.util.TimeFormatUtil;

/** 横屏游戏模式外壳，仅承载 Pad 游戏页。 */
public class PadGameModeActivity extends AppCompatActivity {
    private ActivityPadGameModeBinding binding;
    private final Handler deviceStatusHandler = new Handler(Looper.getMainLooper());
    private boolean deviceStatusReceiverRegistered;
    private int batteryLevel = -1;
    private final Runnable deviceStatusTicker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            renderDeviceStatus();
            scheduleNextDeviceStatusUpdate();
        }
    };
    private final BroadcastReceiver deviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryLevel(intent);
            renderDeviceStatus();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureLandscapeWindow();

        binding = ActivityPadGameModeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        configureParticleLayer();
        renderParticles();
        bindActions();
        ensureScanFragment();

        showGamePage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderActionIconTones();
        renderParticles();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startDeviceStatusUpdates();
    }

    @Override
    protected void onStop() {
        stopDeviceStatusUpdates();
        super.onStop();
    }

    // Pad 横屏全出血窗口：系统栏着色为页面背景色（LauncherTheme.bg）+ 刘海短边裁切 +
    // 关闭对比度增强。与 LauncherEdgeToEdgeHelper（透明状态栏 + 明暗自适应）语义不同，
    // 故不走 helper（豁免，见 agent.md §8 grep 监控与重构计划 4.7 项 2）。
    private void configureLandscapeWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int backgroundColor = LauncherTheme.bg(this);
        window.setStatusBarColor(backgroundColor);
        window.setNavigationBarColor(backgroundColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!LauncherActivity.isLauncherDarkMode(this)) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void bindActions() {
        binding.navLaunchCenterIcon.setOnClickListener(view -> {
            confirmReturnToPortrait();
        });
        binding.navSettingsIcon.setOnClickListener(view ->
                startActivity(new Intent(this, PadSettingsActivity.class)));
        binding.navScanGamesIcon.setOnClickListener(view -> openScanGames());
        binding.navDiagnosticsIcon.setOnClickListener(view -> openDiagnostics());
        binding.navToneSwitchIcon.setOnClickListener(view -> openToneSwitch());
    }

    private void configureParticleLayer() {
        binding.padLauncherParticleView.setFocusable(false);
        binding.padLauncherParticleView.setClickable(false);
        binding.padLauncherParticleView.setFocusableInTouchMode(false);
    }

    private void renderParticles() {
        if (binding == null) return;
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        binding.padLauncherParticleView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.padLauncherParticleView.setParticleStyle(LauncherActivity.getLauncherParticleStyle(this));
        binding.padLauncherParticleView.setParticlesEnabled(enabled);
    }

    private void confirmReturnToPortrait() {
        PadDialogFactory.showConfirm(
                this,
                getString(R.string.pad_portrait_mode_title),
                getString(R.string.pad_portrait_mode_message),
                getString(R.string.core_confirm),
                () -> {
                    Intent intent = new Intent(this, LauncherActivity.class);
                    intent.putExtra(LauncherIntents.EXTRA_FORCE_PORTRAIT_HOME, true);
                    startActivity(intent);
                    finish();
                }
        );
    }

    private void showGamePage() {
        renderActionIconTones();
        if (getSupportFragmentManager().findFragmentById(R.id.padFragmentContainer) instanceof PadGameFragment) {
            return;
        }
        Fragment fragment = new PadGameFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit,
                        R.anim.launcher_fragment_enter,
                        R.anim.launcher_fragment_exit)
                .replace(R.id.padFragmentContainer, fragment, "pad_game")
                .commit();
    }

    private void openScanGames() {
        Fragment current = getSupportFragmentManager().findFragmentByTag("pad_game_scan");
        if (current instanceof PadGameScanFragment) {
            ((PadGameScanFragment) current).startScan();
        }
    }

    private void openDiagnostics() {
        Fragment current = getSupportFragmentManager().findFragmentByTag("pad_game_scan");
        if (current instanceof PadGameScanFragment) {
            ((PadGameScanFragment) current).showDiagnostics();
        }
    }

    private void openToneSwitch() {
        LauncherToneSwitcher.confirmToggle(this, () -> Toast.makeText(
                this,
                R.string.pad_disable_follow_system_tone_first,
                Toast.LENGTH_SHORT
        ).show());
    }

    private void ensureScanFragment() {
        if (getSupportFragmentManager().findFragmentByTag("pad_game_scan") != null) {
            return;
        }
        getSupportFragmentManager()
                .beginTransaction()
                .add(new PadGameScanFragment(), "pad_game_scan")
                .commitNow();
    }

    private void renderActionIconTones() {
        applyQuickActionThemeIcon(binding.navSettingsIcon);
        applyQuickActionThemeIcon(binding.navScanGamesIcon);
        applyQuickActionThemeIcon(binding.navDiagnosticsIcon);
        applyQuickActionThemeIcon(binding.navToneSwitchIcon);
        applyQuickActionThemeIcon(binding.navLaunchCenterIcon);
        binding.padDeviceStatus.setTextColor(LauncherTheme.textMuted(this));
    }

    private void applyQuickActionThemeIcon(ImageView icon) {
        icon.setBackground(null);
        icon.setColorFilter(LauncherTheme.primary(this));
    }

    private void startDeviceStatusUpdates() {
        if (deviceStatusReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = ContextCompat.registerReceiver(
                this,
                deviceStatusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        deviceStatusReceiverRegistered = true;
        updateBatteryLevel(batteryStatus);
        renderDeviceStatus();
        scheduleNextDeviceStatusUpdate();
    }

    private void stopDeviceStatusUpdates() {
        deviceStatusHandler.removeCallbacks(deviceStatusTicker);
        if (!deviceStatusReceiverRegistered) return;
        unregisterReceiver(deviceStatusReceiver);
        deviceStatusReceiverRegistered = false;
    }

    private void updateBatteryLevel(Intent batteryStatus) {
        if (batteryStatus == null) return;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        batteryLevel = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
    }

    private void renderDeviceStatus() {
        if (binding == null) return;
        String clock = TimeFormatUtil.clock(System.currentTimeMillis());
        if (batteryLevel >= 0) {
            binding.padDeviceStatus.setText(getString(R.string.pad_device_status, batteryLevel, clock));
        } else {
            binding.padDeviceStatus.setText(getString(R.string.pad_device_status_unavailable, clock));
        }
    }

    private void scheduleNextDeviceStatusUpdate() {
        deviceStatusHandler.removeCallbacks(deviceStatusTicker);
        long delay = 60_000L - System.currentTimeMillis() % 60_000L;
        deviceStatusHandler.postDelayed(deviceStatusTicker, delay);
    }

}
