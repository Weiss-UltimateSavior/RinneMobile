package com.apps;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.ai.AiReviewSettings;

public class LauncherAiReviewActivity extends AppCompatActivity {
    private ScrollView scroll;
    private Spinner providerSpinner;
    private Spinner personaSpinner;
    private Spinner spoilerSpinner;
    private EditText baseUrlInput;
    private EditText apiKeyInput;
    private EditText modelInput;
    private EditText temperatureInput;
    private EditText promptInput;
    private CheckBox fullEndpointCheck;
    private CheckBox metadataEnhanceCheck;
    private CheckBox metadataOnlineCheck;
    private TextView btnGenerate;
    private TextView btnHistory;
    private TextView btnSave;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_ai_review);

        bindViews();
        applySystemBarInsets();
        setupSpinners();
        loadSettings();
        bindActions();
        applyThemeTone();
    }

    private void bindViews() {
        scroll = findViewById(R.id.aiReviewScroll);
        providerSpinner = findViewById(R.id.aiReviewProviderSpinner);
        personaSpinner = findViewById(R.id.aiReviewPersonaSpinner);
        spoilerSpinner = findViewById(R.id.aiReviewSpoilerSpinner);
        baseUrlInput = findViewById(R.id.aiReviewBaseUrlInput);
        apiKeyInput = findViewById(R.id.aiReviewApiKeyInput);
        modelInput = findViewById(R.id.aiReviewModelInput);
        temperatureInput = findViewById(R.id.aiReviewTemperatureInput);
        promptInput = findViewById(R.id.aiReviewPromptInput);
        fullEndpointCheck = findViewById(R.id.aiReviewFullEndpointCheck);
        metadataEnhanceCheck = findViewById(R.id.aiReviewMetadataEnhanceCheck);
        metadataOnlineCheck = findViewById(R.id.aiReviewMetadataOnlineCheck);
        btnGenerate = findViewById(R.id.aiReviewGenerate);
        btnHistory = findViewById(R.id.aiReviewHistory);
        btnSave = findViewById(R.id.aiReviewSave);
    }

    private void setupSpinners() {
        setupSpinner(providerSpinner, new String[]{"DeepSeek", "OpenAI", "自定义"});
        setupSpinner(personaSpinner, new String[]{"小恶魔妹妹", "温柔学姐", "冷面鉴赏家", "自定义"});
        setupSpinner(spoilerSpinner, new String[]{"严格", "适中", "开放"});
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String provider = providerValue(String.valueOf(providerSpinner.getSelectedItem()));
                if (baseUrlInput.getText() == null || baseUrlInput.getText().toString().trim().isEmpty()) {
                    baseUrlInput.setText(AiReviewSettings.defaultBaseUrl(provider));
                }
                if (modelInput.getText() == null || modelInput.getText().toString().trim().isEmpty()) {
                    modelInput.setText(AiReviewSettings.defaultModel(provider));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        personaSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String persona = AiReviewSettings.personaValue(String.valueOf(personaSpinner.getSelectedItem()));
                if (!AiReviewSettings.PERSONA_CUSTOM.equals(persona)
                        && (promptInput.getText() == null || promptInput.getText().toString().trim().isEmpty())) {
                    promptInput.setText(AiReviewSettings.promptForPersona(persona));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupSpinner(Spinner spinner, String[] values) {
        ArrayAdapter<String> adapter = LauncherTheme.spinnerAdapter(this, values);
        spinner.setAdapter(adapter);
        LauncherTheme.styleSpinner(spinner);
    }

    private void loadSettings() {
        AiReviewSettings settings = AiReviewSettings.load(this);
        selectValue(providerSpinner, AiReviewSettings.providerLabel(settings.provider));
        selectValue(personaSpinner, AiReviewSettings.personaLabel(settings.personaPreset));
        selectValue(spoilerSpinner, AiReviewSettings.spoilerLabel(settings.spoilerLevel));
        baseUrlInput.setText(settings.baseUrl);
        apiKeyInput.setText(settings.apiKey);
        modelInput.setText(settings.model);
        temperatureInput.setText(String.valueOf(settings.temperature));
        promptInput.setText(settings.systemPrompt);
        fullEndpointCheck.setChecked(settings.fullEndpointUrl);
        metadataEnhanceCheck.setChecked(settings.metadataEnhance);
        metadataOnlineCheck.setChecked(settings.metadataOnlineLookup);
    }

    private void bindActions() {
        btnSave.setOnClickListener(view -> {
            saveSettings();
            Toast.makeText(this, "智能评价配置已保存", Toast.LENGTH_SHORT).show();
        });
        btnGenerate.setOnClickListener(view -> {
            saveSettings();
            startActivity(new Intent(this, LauncherAiReviewGenerateActivity.class));
        });
        btnHistory.setOnClickListener(view ->
                startActivity(new Intent(this, LauncherAiReviewHistoryActivity.class)));
    }

    private void saveSettings() {
        AiReviewSettings settings = new AiReviewSettings();
        settings.provider = providerValue(String.valueOf(providerSpinner.getSelectedItem()));
        settings.baseUrl = textOf(baseUrlInput);
        settings.apiKey = textOf(apiKeyInput);
        settings.model = textOf(modelInput);
        settings.personaPreset = AiReviewSettings.personaValue(String.valueOf(personaSpinner.getSelectedItem()));
        settings.spoilerLevel = spoilerValue(String.valueOf(spoilerSpinner.getSelectedItem()));
        settings.temperature = parseTemperature(textOf(temperatureInput));
        settings.systemPrompt = textOf(promptInput);
        settings.fullEndpointUrl = fullEndpointCheck.isChecked();
        settings.metadataEnhance = metadataEnhanceCheck.isChecked();
        settings.metadataOnlineLookup = metadataOnlineCheck.isChecked();
        settings.save(this);
    }

    private void selectValue(Spinner spinner, String value) {
        if (spinner == null || value == null) return;
        for (int i = 0; i < spinner.getCount(); i++) {
            if (value.equals(String.valueOf(spinner.getItemAtPosition(i)))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private String providerValue(String label) {
        if ("OpenAI".equalsIgnoreCase(label)) return AiReviewSettings.PROVIDER_OPENAI;
        if ("自定义".equals(label)) return AiReviewSettings.PROVIDER_CUSTOM;
        return AiReviewSettings.PROVIDER_DEEPSEEK;
    }

    private String spoilerValue(String label) {
        if ("开放".equals(label)) return "open";
        if ("适中".equals(label)) return "mild";
        return "strict";
    }

    private float parseTemperature(String text) {
        try {
            return Float.parseFloat(text);
        } catch (Throwable ignored) {
            return 0.85f;
        }
    }

    private String textOf(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void applyThemeTone() {
        btnGenerate.setBackground(LauncherTheme.primaryButton(this, 24f));
        btnSave.setBackground(LauncherTheme.primaryButton(this, 24f));
        btnHistory.setBackground(LauncherTheme.cancelChip(this));
        LauncherTheme.textOnPrimary(btnGenerate);
        LauncherTheme.textOnPrimary(btnSave);
        LauncherTheme.textPrimary(btnHistory);
        LauncherTheme.applyPrimaryTone(findViewById(android.R.id.content));
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

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
