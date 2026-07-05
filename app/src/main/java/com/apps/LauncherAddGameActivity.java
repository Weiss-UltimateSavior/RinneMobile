package com.apps;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.R;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.scanner.EngineDetector;
import com.yuki.yukihub.util.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class LauncherAddGameActivity extends AppCompatActivity {
    private ScrollView scroll;
    private EditText nameInput;
    private EditText launchTargetInput;
    private EditText emulatorInput;
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
        launchTargetInput = findViewById(R.id.addGameLaunchTargetInput);
        emulatorInput = findViewById(R.id.addGameEmulatorInput);
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
        ArrayAdapter<EngineOption> adapter = new ArrayAdapter<EngineOption>(
                this,
                android.R.layout.simple_spinner_item,
                options
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        engineSpinner.setAdapter(adapter);
        engineSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                EngineType engine = selectedEngine();
                String nextDefault = defaultEmulatorPackage(engine);
                String current = textOf(emulatorInput);
                if (current.isEmpty() || current.equals(lastEngineDefaultPackage)) {
                    emulatorInput.setText(nextDefault);
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
        String selectedLaunchTarget = textOf(launchTargetInput);
        String selectedEmulator = textOf(emulatorInput);
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

    private String textOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
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
}
