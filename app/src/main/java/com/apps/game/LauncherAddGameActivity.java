package com.apps.game;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherCoverBridge;
import com.yuki.yukihub.launcherbridge.LauncherGameHubShortcutBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.launcherbridge.LauncherScanBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import rikka.shizuku.Shizuku;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherAddGameActivity extends AppCompatActivity {
    private ScrollView scroll;
    private EditText nameInput;
    private TextView launchTargetText;
    private String launchTargetName = "";
    private TextView emulatorText;
    private EditText gameHubIdInput;
    private ImageView importGameHubShortcutButton;
    private EditText descriptionInput;
    private TextView dirText;
    private TextView coverText;
    private TextView saveButton;
    private Spinner engineSpinner;
    private Uri gameDirUri;
    private Uri coverUri;
    private String lastEngineDefaultPackage = "";
    private static final int SHIZUKU_GAMEHUB_PERMISSION_REQUEST = 62002;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_GAMEHUB_PERMISSION_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) importGameHubShortcutFromShizuku();
                else Toast.makeText(this, "未获得 Shizuku 授权，仍可手动填写 localGameId", Toast.LENGTH_LONG).show();
            };

    private final ActivityResultLauncher<Uri> directoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                persistUriPermission(uri);
                gameDirUri = uri;
                dirText.setText(displayUri(uri));
                fillTitleFromDirIfEmpty(uri);
                launchTargetName = "";
                launchTargetText.setText("点击选择启动文件");
            });

    private final ActivityResultLauncher<String[]> coverPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                persistUriPermission(uri);
                coverUri = uri;
                coverText.setText(displayUri(uri));
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_add_game);
        LauncherTabletPortraitScaler.applyActivityContent(this);

        bindViews();
        applySystemBarInsets();
        setupEngineSpinner();
        bindActions();
        applyThemeTone();
        try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) { }
    }

    private void bindViews() {
        scroll = findViewById(R.id.addGameScroll);
        nameInput = findViewById(R.id.addGameNameInput);
        launchTargetText = findViewById(R.id.addGameLaunchTargetInput);
        emulatorText = findViewById(R.id.addGameEmulatorInput);
        gameHubIdInput = findViewById(R.id.addGameGameHubIdInput);
        importGameHubShortcutButton = findViewById(R.id.addGameImportGameHubShortcut);
        descriptionInput = findViewById(R.id.addGameDescriptionInput);
        dirText = findViewById(R.id.addGameDirText);
        coverText = findViewById(R.id.addGameCoverText);
        saveButton = findViewById(R.id.addGameSave);
        engineSpinner = findViewById(R.id.addGameEngineSpinner);
    }

    private void applySystemBarInsets() {
        int originalLeft = scroll.getPaddingLeft();
        int originalTop = scroll.getPaddingTop();
        int originalRight = scroll.getPaddingRight();
        int originalBottom = scroll.getPaddingBottom();
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        scroll.requestApplyInsets();
    }

    private void setupEngineSpinner() {
        EngineOption[] options = new EngineOption[]{
                new EngineOption(EngineType.AUTO, "自动识别"),
                new EngineOption(EngineType.KIRIKIRI, "Kirikiri"),
                new EngineOption(EngineType.ONS, "ONScripter"),
                new EngineOption(EngineType.TYRANO, "Tyrano"),
                new EngineOption(EngineType.ARTEMIS, "Artemis"),
                new EngineOption(EngineType.WINLATOR, "Winlator"),
                new EngineOption(EngineType.GAMEHUB, "GameHub"),
                new EngineOption(EngineType.PSP, "PSP")
        };
        ArrayAdapter<EngineOption> adapter = LauncherTheme.spinnerAdapter(this, options);
        engineSpinner.setAdapter(adapter);
        LauncherTheme.styleSpinner(engineSpinner);
        engineSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                EngineType engine = selectedEngine();
                String nextDefault = defaultEmulatorPackage(engine);
                String current = textOf(emulatorText);
                if (current.isEmpty() || current.equals(lastEngineDefaultPackage)) {
                    emulatorText.setText(nextDefault);
                }
                lastEngineDefaultPackage = nextDefault;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void bindActions() {
        dirText.setOnClickListener(view -> directoryPicker.launch(null));
        launchTargetText.setOnClickListener(view -> showLaunchTargetPicker());
        emulatorText.setOnClickListener(view -> showAppPicker(emulatorText));
        importGameHubShortcutButton.setOnClickListener(view -> importGameHubShortcutFromShizuku());
        coverText.setOnClickListener(view -> coverPicker.launch(new String[]{"image/*"}));
        saveButton.setOnClickListener(view -> saveGame());
    }

    private void applyThemeTone() {
        LauncherTheme.longActionButton(saveButton);
        LauncherTheme.applyPrimaryTone(findViewById(android.R.id.content));
        LauncherTheme.formInputs(nameInput, gameHubIdInput, descriptionInput);
        importGameHubShortcutButton.setImageTintList(
                ColorStateList.valueOf(LauncherTheme.primary(this)));
    }

    private void importGameHubShortcutFromShizuku() {
        if (selectedEngine() != EngineType.GAMEHUB) {
            Toast.makeText(this, "请先将游戏引擎设为 GameHub", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LauncherGameHubShortcutBridge.isShizukuRunning()) {
            Toast.makeText(this, "请先启动 Shizuku，再授权读取盖世快捷方式", Toast.LENGTH_LONG).show();
            return;
        }
        if (!LauncherGameHubShortcutBridge.hasShizukuPermission()) {
            try { LauncherGameHubShortcutBridge.requestShizukuPermission(SHIZUKU_GAMEHUB_PERMISSION_REQUEST); }
            catch (Throwable error) { Toast.makeText(this, "无法请求 Shizuku 授权", Toast.LENGTH_LONG).show(); }
            return;
        }
        importGameHubShortcutButton.setEnabled(false);
        importGameHubShortcutButton.setAlpha(0.45f);
        importGameHubShortcutButton.setContentDescription("正在读取 GameHub 快捷方式");
        AppExecutors.runOnIo(() -> {
            List<LauncherGameHubShortcutBridge.Shortcut> items;
            try { items = LauncherGameHubShortcutBridge.loadShortcuts(); }
            catch (Throwable ignored) { items = new ArrayList<>(); }
            final List<LauncherGameHubShortcutBridge.Shortcut> shortcuts = items;
            runOnUiThread(() -> {
                importGameHubShortcutButton.setEnabled(true);
                importGameHubShortcutButton.setAlpha(1f);
                importGameHubShortcutButton.setContentDescription("导入 GameHub 快捷方式");
                if (isFinishing()) return;
                if (shortcuts.isEmpty()) {
                    Toast.makeText(this, "未读取到快捷方式；请确认盖世已创建桌面快捷方式", Toast.LENGTH_LONG).show();
                    return;
                }
                CharSequence[] labels = new CharSequence[shortcuts.size()];
                for (int i = 0; i < shortcuts.size(); i++) labels[i] = shortcuts.get(i).displayLabel + "\n" + shortcuts.get(i).localGameId;
                com.apps.theme.LauncherDialogFactory.showActionChoices(this, "选择盖世快捷方式",
                        labels, which -> applyGameHubShortcut(shortcuts.get(which)));
            });
        });
    }

    private void applyGameHubShortcut(LauncherGameHubShortcutBridge.Shortcut item) {
        if (item == null) return;
        gameHubIdInput.setText(item.localGameId);
        if (textOf(nameInput).isEmpty()) nameInput.setText(item.localAppName);
        if (textOf(emulatorText).isEmpty()) emulatorText.setText("com.xiaoji.egggamz");
    }

    /** 扫描游戏目录下的相关游戏文件，弹出列表供用户选择启动入口。 */
    private void showLaunchTargetPicker() {
        if (gameDirUri == null) {
            Toast.makeText(this, "请先选择游戏目录", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(this);
        title.setText("选择启动文件");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(this);
        status.setText("正在扫描游戏文件...");
        status.setGravity(android.view.Gravity.CENTER);
        status.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        status.setTextSize(13);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(13), 0, 0);
        root.addView(status, statusLp);

        window.setContentView(root);

        android.content.Context appContext = getApplicationContext();
        Uri dirUri = gameDirUri;
        AppExecutors.runOnIo(() -> {
            List<String> files = scanGameFiles(appContext, dirUri);
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                root.removeView(status);
                if (files.isEmpty()) {
                    status.setText("未找到游戏文件");
                    root.addView(status, statusLp);
                } else {
                    ScrollView scroll = new ScrollView(this);
                    LinearLayout list = new LinearLayout(this);
                    list.setOrientation(LinearLayout.VERTICAL);
                    for (String file : files) {
                        TextView item = new TextView(this);
                        item.setText(file);
                        item.setGravity(android.view.Gravity.CENTER);
                        item.setSingleLine(true);
                        item.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        item.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
                        item.setTextSize(13);
                        item.setBackground(LauncherTheme.cancelChip(this));
                        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
                        itemLp.setMargins(0, dp(7), 0, 0);
                        String selected = file;
                        item.setOnClickListener(v -> {
                            launchTargetName = selected;
                            launchTargetText.setText(selected);
                            dialog.dismiss();
                        });
                        list.addView(item, itemLp);
                    }
                    scroll.addView(list);
                    int maxScrollHeight = dp(280);
                    int listHeight = dp(7) + files.size() * (dp(38) + dp(7));
                    LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            Math.min(listHeight, maxScrollHeight));
                    scrollLp.setMargins(0, dp(7), 0, 0);
                    root.addView(scroll, scrollLp);
                }
                TextView cancel = new TextView(this);
                cancel.setText("取消");
                cancel.setGravity(android.view.Gravity.CENTER);
                cancel.setTextColor(LauncherTheme.primary(this));
                cancel.setTextSize(13);
                cancel.setTypeface(null, android.graphics.Typeface.BOLD);
                cancel.setBackground(LauncherTheme.cancelChip(this));
                LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
                cancelLp.setMargins(0, dp(9), 0, 0);
                cancel.setOnClickListener(v -> dialog.dismiss());
                root.addView(cancel, cancelLp);
            });
        });
    }

    /** 扫描游戏目录（深度2层）收集相关游戏文件。 */
    private List<String> scanGameFiles(android.content.Context context, Uri dirUri) {
        List<String> result = new ArrayList<>();
        if (dirUri == null) return result;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, dirUri);
            if (root == null) return result;
            collectGameFiles(root, "", 1, 2, result);
        } catch (Throwable ignored) {
        }
        return result;
    }

    private void collectGameFiles(DocumentFile dir, String prefix, int level, int maxLevel, List<String> result) {
        DocumentFile[] files;
        try {
            if (dir == null || !dir.isDirectory()) return;
            files = dir.listFiles();
        } catch (Throwable ignored) {
            return;
        }
        if (files == null) return;
        for (DocumentFile f : files) {
            if (f == null) continue;
            String name = safeName(f);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;
            boolean isDirectory = false;
            try { isDirectory = f.isDirectory(); } catch (Throwable ignored) { }
            if (isDirectory) {
                if (level < maxLevel) {
                    collectGameFiles(f, prefix.isEmpty() ? name : prefix + "/" + name,
                            level + 1, maxLevel, result);
                }
                continue;
            }
            if (isGameFile(lower)) {
                result.add(prefix.isEmpty() ? name : prefix + "/" + name);
            }
        }
    }

    private boolean isGameFile(String lowerName) {
        if (lowerName.endsWith(".xp3") || lowerName.endsWith(".pfs")
                || lowerName.endsWith(".iso") || lowerName.endsWith(".cso")
                || lowerName.endsWith(".chd") || lowerName.endsWith(".elf")
                || lowerName.endsWith(".pbp") || lowerName.endsWith(".desktop")
                || lowerName.endsWith(".exe")) {
            return true;
        }
        return lowerName.equals("0.txt") || lowerName.equals("00.txt")
                || lowerName.equals("nscript.dat") || lowerName.equals("nscr_sec.dat")
                || lowerName.equals("onscript.nt2") || lowerName.equals("onscript.nt3")
                || lowerName.equals("index.html") || lowerName.equals("startup.tjs");
    }

    private String safeName(DocumentFile file) {
        try {
            String name = file == null ? null : file.getName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void saveGame() {
        String title = textOf(nameInput);
        if (title.isEmpty()) {
            Toast.makeText(this, "请填写游戏名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (gameDirUri == null) {
            Toast.makeText(this, "请选择游戏目录", Toast.LENGTH_SHORT).show();
            return;
        }

        saveButton.setEnabled(false);
        saveButton.setText("保存中...");

        android.content.Context appContext = getApplicationContext();
        EngineType selectedEngine = selectedEngine();
        String selectedLaunchTarget = launchTargetName;
        String selectedEmulator = textOf(emulatorText);
        String selectedGameHubId = textOf(gameHubIdInput);
        String selectedDescription = textOf(descriptionInput);
        Uri selectedGameDir = gameDirUri;
        Uri selectedCover = coverUri;
        AppExecutors.runOnSingle(() -> {
            LauncherScanBridge.DetectionResult detected = null;
            if (selectedEngine == EngineType.AUTO) {
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(appContext, selectedGameDir);
                    detected = LauncherScanBridge.detectEngine(root, 2);
                } catch (Throwable ignored) {
                }
            }
            EngineType finalEngine = selectedEngine;
            if (detected != null && detected.confidence > 0 && detected.engine != EngineType.UNKNOWN) {
                finalEngine = detected.engine;
            }

            Game game = new Game();
            game.title = title;
            game.engine = finalEngine;
            game.rootUri = selectedGameDir.toString();
            String copiedCover = copyCoverToInternalStorage(selectedCover);
            game.coverUri = copiedCover;
            game.coverPersistUri = copiedCover;
            game.coverSourceType = copiedCover == null ? 0 : 1;
            game.launchTarget = textOrDefault(
                    selectedLaunchTarget,
                    detected != null && detected.launchTarget != null && !detected.launchTarget.trim().isEmpty()
                            ? detected.launchTarget
                            : "[游戏目录]"
            );
            game.emulatorPackage = textOrDefault(selectedEmulator, defaultEmulatorPackage(finalEngine));
            game.description = selectedDescription;
            game.gamehubLocalGameId = selectedGameHubId;
            if (game.engine == EngineType.GAMEHUB && selectedGameHubId.isEmpty()) {
                game.gamehubLaunchMode = "program";
            }

            long id = LauncherRepositoryBridge.insertGameIfNotExists(appContext, game);
            if (id > 0 && copiedCover == null) {
                game.id = id;
                LauncherCoverBridge.fetchCoverForGameAsync(appContext, game);
            }
            runOnUiThread(() -> {
                saveButton.setEnabled(true);
                saveButton.setText("保存");
                if (id > 0) {
                    Toast.makeText(this, "游戏已添加", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, "保存失败：该目录可能已经在游戏库中", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private EngineType selectedEngine() {
        Object selected = engineSpinner.getSelectedItem();
        if (selected instanceof EngineOption) return ((EngineOption) selected).engine;
        return EngineType.AUTO;
    }

    private String defaultEmulatorPackage(EngineType engine) {
        if (engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (engine == EngineType.ONS) return "internal.ons";
        if (engine == EngineType.TYRANO) return "internal.tyrano";
        if (engine == EngineType.ARTEMIS) return "internal.artemis";
        if (engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        if (engine == EngineType.GAMEHUB) return "com.xiaoji.egggamz";
        return "";
    }

    private String copyCoverToInternalStorage(Uri uri) {
        if (uri == null) return null;
        Bitmap bitmap = null;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) return null;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int max = 720;
            if (width > max || height > max) {
                float scale = Math.min(max / (float) width, max / (float) height);
                Bitmap scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        Math.max(1, (int) (width * scale)),
                        Math.max(1, (int) (height * scale)),
                        true
                );
                bitmap.recycle();
                bitmap = scaled;
            }
            File dir = new File(getFilesDir(), "covers");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, "cover_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, outputStream);
                outputStream.flush();
            }
            return Uri.fromFile(file).toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void persistUriPermission(Uri uri) {
        if (uri == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Throwable first) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
    }

    private void fillTitleFromDirIfEmpty(Uri uri) {
        if (!textOf(nameInput).isEmpty() || uri == null) return;
        String display = displayUri(uri);
        int slash = display.lastIndexOf('/');
        String title = slash >= 0 && slash < display.length() - 1 ? display.substring(slash + 1) : display;
        if (title.startsWith("primary:")) title = title.substring("primary:".length());
        if (!title.trim().isEmpty()) nameInput.setText(title.trim());
    }

    private String displayUri(Uri uri) {
        if (uri == null) return "";
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null && !docId.trim().isEmpty()) return Uri.decode(docId);
        } catch (Throwable ignored) {
        }
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null && !docId.trim().isEmpty()) return Uri.decode(docId);
        } catch (Throwable ignored) {
        }
        return uri.toString();
    }

    private String textOf(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private String textOf(TextView textView) {
        return textView == null || textView.getText() == null ? "" : textView.getText().toString().trim();
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void showAppPicker(TextView target) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_launcher_app_picker);
        LauncherTheme.applyPrimaryTone(dialog.findViewById(android.R.id.content));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.74f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
        RecyclerView rv = dialog.findViewById(R.id.recyclerLauncherAppPicker);
        View loading = dialog.findViewById(R.id.layoutLauncherAppLoading);
        TextView hint = dialog.findViewById(R.id.tvLauncherAppPickerHint);
        EditText search = dialog.findViewById(R.id.etLauncherAppSearch);
        TextView btnClose = dialog.findViewById(R.id.btnCloseLauncherAppPicker);
        LauncherTheme.secondaryButton(btnClose);
        rv.setLayoutManager(new LinearLayoutManager(this));
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.74f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }

        AppExecutors.runOnIo(() -> {
            List<AppPickItem> items = loadLaunchableApps();
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                loading.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
                if (items.isEmpty()) {
                    hint.setText("没有找到可启动的应用");
                    return;
                }
                hint.setText("共 " + items.size() + " 个可启动应用，可搜索应用名或包名");
                final AppPickerAdapter[] adapterRef = new AppPickerAdapter[1];
                adapterRef[0] = new AppPickerAdapter(items, item -> {
                    target.setText(item.packageName);
                    dialog.dismiss();
                });
                rv.setAdapter(adapterRef[0]);
                search.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                        if (adapterRef[0] == null) return;
                        adapterRef[0].filter(s == null ? "" : s.toString());
                        hint.setText("共 " + items.size() + " 个应用，当前显示 " + adapterRef[0].getItemCount() + " 个");
                    }
                    public void afterTextChanged(Editable e) {}
                });
            });
        });
    }

    private List<AppPickItem> loadLaunchableApps() {
        LinkedHashMap<String, AppPickItem> map = new LinkedHashMap<>();
        try {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchers = pm.queryIntentActivities(launcher, 0);
            if (launchers != null) {
                for (ResolveInfo ri : launchers) {
                    if (ri == null || ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    addAppPickItem(map, pm, ri.activityInfo.applicationInfo);
                }
            }
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (apps != null) {
                for (ApplicationInfo app : apps) {
                    if (app == null || app.packageName == null) continue;
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) addAppPickItem(map, pm, app);
                }
            }
        } catch (Throwable ignored) {
        }
        List<AppPickItem> items = new ArrayList<>(map.values());
        items.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return items;
    }

    private void addAppPickItem(Map<String, AppPickItem> map, PackageManager pm, ApplicationInfo app) {
        if (map == null || pm == null || app == null || app.packageName == null) return;
        if (map.containsKey(app.packageName)) return;
        try {
            CharSequence labelSeq = pm.getApplicationLabel(app);
            String label = labelSeq == null ? app.packageName : labelSeq.toString();
            Drawable icon = pm.getApplicationIcon(app);
            map.put(app.packageName, new AppPickItem(label, app.packageName, icon));
        } catch (Throwable ignored) {
        }
    }

    private interface AppPickCallback {
        void onPick(AppPickItem item);
    }

    private static final class AppPickItem {
        final String label;
        final String packageName;
        final Drawable icon;

        AppPickItem(String label, String packageName, Drawable icon) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.icon = icon;
        }
    }

    private class AppPickerAdapter extends RecyclerView.Adapter<AppPickerAdapter.Holder> {
        private final List<AppPickItem> allItems;
        private final List<AppPickItem> items = new ArrayList<>();
        private final AppPickCallback callback;

        AppPickerAdapter(List<AppPickItem> items, AppPickCallback callback) {
            this.allItems = items == null ? new ArrayList<>() : new ArrayList<>(items);
            this.items.addAll(this.allItems);
            this.callback = callback;
        }

        void filter(String query) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            items.clear();
            if (q.isEmpty()) {
                items.addAll(allItems);
            } else {
                for (AppPickItem item : allItems) {
                    String label = item.label == null ? "" : item.label.toLowerCase(Locale.ROOT);
                    String pkg = item.packageName == null ? "" : item.packageName.toLowerCase(Locale.ROOT);
                    if (label.contains(q) || pkg.contains(q)) items.add(item);
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_launcher_app_picker, parent, false);
            LauncherTabletPortraitScaler.apply(v);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    Math.round(dp(68) * LauncherTabletPortraitScaler.scaleFor(v)));
            lp.setMargins(0, 0, 0, dp(7));
            v.setLayoutParams(lp);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AppPickItem item = items.get(position);
            h.label.setText(item.label.isEmpty() ? item.packageName : item.label);
            h.pkg.setText(item.packageName);
            if (item.icon != null) {
                h.icon.setImageDrawable(item.icon);
            } else {
                h.icon.setImageResource(android.R.mipmap.sym_def_app_icon);
            }
            h.itemView.setOnClickListener(v -> {
                if (callback != null) callback.onPick(item);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView label, pkg;

            Holder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.ivLauncherAppIcon);
                label = itemView.findViewById(R.id.tvLauncherAppLabel);
                pkg = itemView.findViewById(R.id.tvLauncherAppPackage);
            }
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private static final class EngineOption {
        final EngineType engine;
        final String label;

        EngineOption(EngineType engine, String label) {
            this.engine = engine;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
