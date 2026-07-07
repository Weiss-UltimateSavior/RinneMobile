package com.apps;

import android.app.Dialog;
import android.content.Intent;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.launcherbridge.LauncherCoverBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.scanner.EngineDetector;
import com.yuki.yukihub.util.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LauncherAddGameActivity extends AppCompatActivity {
    private ScrollView scroll;
    private EditText nameInput;
    private TextView launchTargetText;
    private Uri launchTargetUri;
    private TextView emulatorText;
    private EditText descriptionInput;
    private TextView dirText;
    private TextView coverText;
    private TextView saveButton;
    private Spinner engineSpinner;
    private Uri gameDirUri;
    private Uri coverUri;
    private String lastEngineDefaultPackage = "";

    private final ActivityResultLauncher<Uri> directoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                persistUriPermission(uri);
                gameDirUri = uri;
                dirText.setText(displayUri(uri));
                fillTitleFromDirIfEmpty(uri);
            });

    private final ActivityResultLauncher<String[]> launchTargetPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                persistUriPermission(uri);
                launchTargetUri = uri;
                launchTargetText.setText(displayUri(uri));
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

        bindViews();
        applySystemBarInsets();
        setupEngineSpinner();
        bindActions();
        applyThemeTone();
    }

    private void bindViews() {
        scroll = findViewById(R.id.addGameScroll);
        nameInput = findViewById(R.id.addGameNameInput);
        launchTargetText = findViewById(R.id.addGameLaunchTargetInput);
        emulatorText = findViewById(R.id.addGameEmulatorInput);
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
        launchTargetText.setOnClickListener(view -> launchTargetPicker.launch(new String[]{"*/*"}));
        emulatorText.setOnClickListener(view -> showAppPicker(emulatorText));
        coverText.setOnClickListener(view -> coverPicker.launch(new String[]{"image/*"}));
        saveButton.setOnClickListener(view -> saveGame());
    }

    private void applyThemeTone() {
        saveButton.setBackground(LauncherTheme.primaryButton(this, 24f));
        LauncherTheme.applyPrimaryTone(findViewById(android.R.id.content));
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
        String selectedLaunchTarget = launchTargetUri != null
                ? extractFileName(launchTargetUri)
                : "";
        String selectedEmulator = textOf(emulatorText);
        String selectedDescription = textOf(descriptionInput);
        Uri selectedGameDir = gameDirUri;
        Uri selectedCover = coverUri;
        AppExecutors.runOnSingle(() -> {
            EngineDetector.Result detected = null;
            if (selectedEngine == EngineType.AUTO) {
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(appContext, selectedGameDir);
                    detected = EngineDetector.detect(root, 2);
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
            if (game.engine == EngineType.GAMEHUB && (game.gamehubLocalGameId == null || game.gamehubLocalGameId.trim().isEmpty())) {
                game.gamehubLaunchMode = "program";
            }

            GameRepository repository = new GameRepository(appContext);
            long id = repository.insertIfNotExists(game);
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

    private String extractFileName(Uri uri) {
        if (uri == null) return "";
        try {
            DocumentFile docFile = DocumentFile.fromSingleUri(this, uri);
            if (docFile != null && docFile.getName() != null) return docFile.getName();
        } catch (Throwable ignored) {
        }
        String display = displayUri(uri);
        int slash = display.lastIndexOf('/');
        return slash >= 0 && slash < display.length() - 1 ? display.substring(slash + 1) : display;
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
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, dp(68));
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
}
