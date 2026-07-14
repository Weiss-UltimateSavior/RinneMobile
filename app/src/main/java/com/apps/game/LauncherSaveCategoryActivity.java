package com.apps.game;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherSaveCategoryBinding;
import com.yuki.yukihub.databinding.ItemLauncherManageBinding;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** First-level save screen: groups the library before opening file operations. */
public class LauncherSaveCategoryActivity extends AppCompatActivity {
    private ActivityLauncherSaveCategoryBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLauncherSaveCategoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        loadCategories();
    }

    private void loadCategories() {
        AppExecutors.runOnSingle(() -> {
            List<Game> games = LauncherRepositoryBridge.getAllGames(this);
            Map<EngineType, Integer> counts = new LinkedHashMap<>();
            for (Game game : games) {
                EngineType engine = game == null || game.engine == null ? EngineType.UNKNOWN : game.engine;
                if (!isSupportedBuiltInGame(game)) continue;
                counts.put(engine, (counts.containsKey(engine) ? counts.get(engine) : 0) + 1);
            }
            runOnUiThread(() -> renderCategories(counts));
        });
    }

    private void renderCategories(Map<EngineType, Integer> counts) {
        binding.saveCategoryList.removeAllViews();
        if (counts == null || counts.isEmpty()) {
            binding.saveCategoryStatus.setText("暂无游戏，请先扫描或添加游戏。");
            return;
        }
        binding.saveCategoryStatus.setText("共 " + totalCount(counts) + " 个游戏，按模拟器类型分类。 ");
        for (Map.Entry<EngineType, Integer> entry : counts.entrySet()) addCategory(entry.getKey(), entry.getValue());
    }

    private void addCategory(EngineType engine, int count) {
        ItemLauncherManageBinding itemBinding = ItemLauncherManageBinding.inflate(
                LayoutInflater.from(this), binding.saveCategoryList, false);
        View row = itemBinding.getRoot();
        LauncherTabletPortraitScaler.apply(row);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            Intent intent = new Intent(this, LauncherSaveGameListActivity.class);
            intent.putExtra(LauncherSaveGameListActivity.EXTRA_ENGINE, engine.name());
            startActivity(intent);
            LauncherMotion.applyActivityOpen(this);
        });

        TextView icon = itemBinding.manageItemIcon;
        icon.setText(engineIcon(engine));
        icon.setTextColor(LauncherTheme.onPrimary(this));
        icon.setBackground(LauncherTheme.circle(this));

        TextView title = itemBinding.manageItemTitle;
        title.setText(engineLabel(engine) + " · " + count + " 个游戏");
        LauncherTheme.applyPrimaryTone(row);
        binding.saveCategoryList.addView(row);
    }

    private static int totalCount(Map<EngineType, Integer> counts) {
        int total = 0;
        for (Integer count : counts.values()) total += count == null ? 0 : count;
        return total;
    }

    public static String engineLabel(EngineType engine) {
        if (engine == null) return "其它游戏";
        switch (engine) {
            case KIRIKIRI: return "KRKR";
            case ARTEMIS: return "Artemis";
            case ONS: return "ONS";
            case TYRANO: return "Tyrano";
            default: return engine.name();
        }
    }

    public static boolean isSupportedBuiltInEngine(EngineType engine) {
        return engine == EngineType.KIRIKIRI || engine == EngineType.ARTEMIS
                || engine == EngineType.ONS || engine == EngineType.TYRANO;
    }

    /** Engine type alone is not enough: custom cards may route it to an external package. */
    public static boolean isSupportedBuiltInGame(Game game) {
        if (game == null || !isSupportedBuiltInEngine(game.engine)) return false;
        String pkg = game.emulatorPackage == null ? "" : game.emulatorPackage.trim().toLowerCase(java.util.Locale.ROOT);
        if (pkg.isEmpty()) return true;
        switch (game.engine) {
            case KIRIKIRI:
                return pkg.startsWith("internal.krkr") || "org.tvp.kirikiri2.internal".equals(pkg);
            case ARTEMIS:
                return pkg.startsWith("internal.artemis");
            case ONS:
                return pkg.startsWith("internal.ons") || "com.yuki.yukihub.ons".equals(pkg);
            case TYRANO:
                return pkg.startsWith("internal.tyrano") || "com.yuki.yukihub.tyrano".equals(pkg);
            default:
                return false;
        }
    }

    private static String engineIcon(EngineType engine) {
        if (engine == EngineType.KIRIKIRI) return "K";
        if (engine == EngineType.ARTEMIS) return "A";
        if (engine == EngineType.ONS) return "O";
        if (engine == EngineType.TYRANO) return "T";
        return "G";
    }

    private void applySystemBarInsets() {
        int left = binding.saveCategoryScroll.getPaddingLeft();
        int top = binding.saveCategoryScroll.getPaddingTop();
        int right = binding.saveCategoryScroll.getPaddingRight();
        int bottom = binding.saveCategoryScroll.getPaddingBottom();
        binding.saveCategoryScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.saveCategoryScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.saveCategoryScroll.requestApplyInsets();
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
