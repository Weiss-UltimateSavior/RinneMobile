package com.apps.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.core.R;
import com.core.util.AppExecutors;

import java.io.File;

/**
 * 头像正方形裁剪 Activity：接收原始图片 URI，用户拖动 / 双指缩放调整位置，
 * 点击确定后将裁剪框内的区域裁成正方形 Bitmap，保存为 JPEG 到内部存储并返回结果。
 */
public class AvatarCropActivity extends AppCompatActivity {

    public static final String EXTRA_INPUT_URI = "input_uri";
    public static final String EXTRA_OUTPUT_URI = "output_uri";
    private static final String OUTPUT_FILE_NAME = "launcher_avatar_cropped.jpg";

    private AvatarCropView cropView;
    private TextView confirmButton;
    private boolean saving;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.Theme_YukiHub_Launcher);
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String inputUriString = intent != null ? intent.getStringExtra(EXTRA_INPUT_URI) : null;
        if (inputUriString == null || inputUriString.trim().isEmpty()) {
            Toast.makeText(this, R.string.avatar_load_failed, Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        // 沉浸式：透明状态栏 + launcher_bg_color 作为导航栏背景。
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        View root = buildRoot(inputUriString);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private View buildRoot(String inputUriString) {
        int bgColor = LauncherTheme.bg(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        // 顶部标题"裁剪头像"
        final TextView title = new TextView(this);
        title.setText(R.string.avatar_crop_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(LauncherTheme.text(this));
        final int pad = LauncherTheme.dp(this, 16);
        title.setPadding(pad, pad, pad, pad);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 中部裁剪区域，占据剩余空间
        cropView = new AvatarCropView(this, Uri.parse(inputUriString), () -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, R.string.avatar_load_failed, Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        });
        root.addView(cropView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));

        // 底部按钮栏：取消 / 确定 各占一半权重
        final LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        final int barPad = LauncherTheme.dp(this, 16);
        buttonBar.setPadding(barPad, 0, barPad, 0);

        TextView cancelButton = new TextView(this);
        cancelButton.setText(R.string.core_cancel);
        cancelButton.setGravity(Gravity.CENTER);
        cancelButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        cancelButton.setTypeface(null, Typeface.BOLD);
        cancelButton.setTextColor(LauncherTheme.textMuted(this));
        cancelButton.setOnClickListener(v -> finish());

        TextView confirm = new TextView(this);
        confirm.setText(R.string.core_confirm);
        confirm.setGravity(Gravity.CENTER);
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        confirm.setTypeface(null, Typeface.BOLD);
        confirm.setTextColor(LauncherTheme.primary(this));
        confirm.setOnClickListener(v -> onConfirm());
        this.confirmButton = confirm;

        int btnHeight = LauncherTheme.dp(this, 48);
        int gap = LauncherTheme.dp(this, 8);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, btnHeight, 1f);
        cancelLp.setMarginEnd(gap);
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                0, btnHeight, 1f);
        confirmLp.setMarginStart(gap);
        buttonBar.addView(cancelButton, cancelLp);
        buttonBar.addView(confirm, confirmLp);
        root.addView(buttonBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 系统 inset：标题加 status bar 顶部 inset，按钮栏加 nav bar 底部 inset
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            int bottomInset = insets.getSystemWindowInsetBottom();
            title.setPadding(pad, pad + topInset, pad, pad);
            buttonBar.setPadding(barPad, 0, barPad, bottomInset + LauncherTheme.dp(this, 8));
            return insets;
        });

        LauncherTheme.applyPrimaryTone(root);
        return root;
    }

    /** 点击确定：裁剪 + 保存 JPEG + 返回结果。 */
    private void onConfirm() {
        if (saving) return;
        if (cropView == null) return;
        Bitmap cropped = cropView.getCroppedBitmap();
        if (cropped == null) {
            Toast.makeText(this, R.string.avatar_process_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        if (confirmButton != null) {
            confirmButton.setEnabled(false);
            confirmButton.setTextColor(LauncherTheme.textMuted(this));
        }
        final Bitmap source = cropView.getSourceBitmap();
        final Bitmap output = cropped;
        AppExecutors.runOnIo(() -> {
            File outFile = new File(getFilesDir(), OUTPUT_FILE_NAME);
            String outputUri = AvatarCropOutputWriter.writeAndRecycle(outFile, output, source);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (outputUri != null) {
                    Intent data = new Intent();
                    data.putExtra(EXTRA_OUTPUT_URI, outputUri);
                    setResult(RESULT_OK, data);
                } else {
                    setResult(RESULT_CANCELED);
                }
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cropView != null) cropView.release();
    }
}
