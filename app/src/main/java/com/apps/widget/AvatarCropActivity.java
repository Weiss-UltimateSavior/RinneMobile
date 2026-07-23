package com.apps.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.R;
import com.yuki.yukihub.util.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;

/**
 * 头像正方形裁剪 Activity：接收原始图片 URI，用户拖动 / 双指缩放调整位置，
 * 点击确定后将裁剪框内的区域裁成正方形 Bitmap，保存为 JPEG 到内部存储并返回结果。
 */
public class AvatarCropActivity extends AppCompatActivity {

    public static final String EXTRA_INPUT_URI = "input_uri";
    public static final String EXTRA_OUTPUT_URI = "output_uri";
    private static final String OUTPUT_FILE_NAME = "launcher_avatar_cropped.jpg";

    private CropView cropView;
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
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        configureEdgeToEdgeWindow();
        View root = buildRoot(inputUriString);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    /** 沉浸式：透明状态栏 + launcher_bg_color 作为导航栏背景。 */
    private void configureEdgeToEdgeWindow() {
        Window window = getWindow();
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!LauncherActivity.isLauncherDarkMode(this)) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private View buildRoot(String inputUriString) {
        int bgColor = ContextCompat.getColor(this, R.color.launcher_bg_color);
        int textColor = ContextCompat.getColor(this, R.color.launcher_text_color);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        // 顶部标题"裁剪头像"
        final TextView title = new TextView(this);
        title.setText("裁剪头像");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(textColor);
        final int pad = dp(16);
        title.setPadding(pad, pad, pad, pad);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 中部裁剪区域，占据剩余空间
        cropView = new CropView(this, Uri.parse(inputUriString), () -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        });
        root.addView(cropView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));

        // 底部按钮栏：取消 / 确定 各占一半权重
        final LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        final int barPad = dp(16);
        buttonBar.setPadding(barPad, 0, barPad, 0);

        TextView cancelButton = new TextView(this);
        cancelButton.setText("取消");
        cancelButton.setGravity(Gravity.CENTER);
        cancelButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        cancelButton.setTypeface(null, Typeface.BOLD);
        cancelButton.setTextColor(LauncherTheme.textMuted(this));
        cancelButton.setOnClickListener(v -> finish());

        TextView confirm = new TextView(this);
        confirm.setText("确定");
        confirm.setGravity(Gravity.CENTER);
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        confirm.setTypeface(null, Typeface.BOLD);
        confirm.setTextColor(LauncherTheme.primary(this));
        confirm.setOnClickListener(v -> onConfirm());
        this.confirmButton = confirm;

        int btnHeight = dp(48);
        int gap = dp(8);
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
            buttonBar.setPadding(barPad, 0, barPad, bottomInset + dp(8));
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
            Toast.makeText(this, "图片处理失败", Toast.LENGTH_SHORT).show();
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
            boolean ok = false;
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                ok = output.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            } catch (Throwable ignored) {
            }
            // 仅回收独立副本；若返回的是源 bitmap 本身则交由 CropView.release() 处理
            if (output != source && !output.isRecycled()) {
                output.recycle();
            }
            final boolean success = ok;
            final String outputUri = success ? Uri.fromFile(outFile).toString() : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (success) {
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

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 自定义裁剪 View：绘制图片、半透明遮罩、裁剪框边线与九宫格辅助线，
     * 支持单指拖动与双指缩放，并在缩放/拖动后约束图片始终覆盖裁剪框。
     */
    private static class CropView extends View {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final int MAX_OUTPUT_SIZE = 512;

        private final Uri inputUri;
        private final Runnable onFailure;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final Matrix startMatrix = new Matrix();

        private Bitmap bitmap;
        private int displayWidth;
        private int displayHeight;

        // 裁剪框（屏幕坐标系）
        private float cropLeft;
        private float cropTop;
        private float cropSize;

        private float baseScale = 1f;
        private float minScale = 1f;
        private float maxScale = 5f;

        // 触摸状态
        private float lastX;
        private float lastY;
        private float startDistance;
        private boolean isScaling;

        CropView(Context context, Uri inputUri, Runnable onFailure) {
            super(context);
            this.inputUri = inputUri;
            this.onFailure = onFailure;
            initDisplaySize();
            startLoad();
        }

        private void initDisplaySize() {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            displayWidth = dm.widthPixels;
            displayHeight = dm.heightPixels;
        }

        /** 异步解码：使用 WeakReference 避免线程持有已销毁的 View。 */
        private void startLoad() {
            final WeakReference<CropView> selfRef = new WeakReference<>(this);
            final Context appContext = getContext().getApplicationContext();
            final Uri uri = inputUri;
            AppExecutors.runOnIo(() -> {
                Bitmap b = null;
                try {
                    b = decodeBitmap(appContext, uri);
                } catch (Throwable ignored) {
                }
                final Bitmap result = b;
                MAIN.post(() -> {
                    CropView view = selfRef.get();
                    if (view == null) {
                        if (result != null) result.recycle();
                        return;
                    }
                    view.onBitmapLoaded(result);
                });
            });
        }

        /** 解码原图：先 inJustDecodeBounds 量尺寸，再 inSampleSize 降采样避免 OOM。 */
        private Bitmap decodeBitmap(Context context, Uri uri) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
            } catch (Throwable ignored) {
            }
            int target = Math.min(displayWidth, displayHeight) * 2;
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, target);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private int calculateSampleSize(int w, int h, int target) {
            if (w <= 0 || h <= 0) return 1;
            int sample = 1;
            while ((w / sample) > target || (h / sample) > target) {
                sample *= 2;
            }
            return sample;
        }

        private void onBitmapLoaded(Bitmap result) {
            if (bitmap != null && !bitmap.isRecycled() && bitmap != result) {
                bitmap.recycle();
            }
            bitmap = result;
            if (result == null) {
                if (onFailure != null) onFailure.run();
                return;
            }
            if (getWidth() > 0 && getHeight() > 0) {
                setupCropBox(getWidth(), getHeight());
                computeInitialMatrix();
            }
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                setupCropBox(w, h);
                if (bitmap != null) {
                    computeInitialMatrix();
                }
            }
        }

        /** 裁剪框：屏幕宽度的 80%，横向居中，纵向中心位于 View 高度的 45% 处。 */
        private void setupCropBox(int vw, int vh) {
            cropSize = vw * 0.8f;
            cropLeft = (vw - cropSize) / 2f;
            float cropCenterY = vh * 0.45f;
            cropTop = cropCenterY - cropSize / 2f;
        }

        /** 初始矩阵：fitCenter 到 View，并放大到至少覆盖裁剪框，使裁剪框中心对齐图片中心。 */
        private void computeInitialMatrix() {
            if (bitmap == null) return;
            int vw = getWidth();
            int vh = getHeight();
            if (vw == 0 || vh == 0) return;
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            float fitScale = Math.min((float) vw / bw, (float) vh / bh);
            float coverScale = Math.max(cropSize / bw, cropSize / bh);
            float scale = Math.max(fitScale, coverScale);
            float scaledBw = bw * scale;
            float scaledBh = bh * scale;
            // 先让 bitmap 居中 View，再平移使 bitmap 中心对齐裁剪框中心
            float dx = (vw - scaledBw) / 2f;
            float dy = (vh - scaledBh) / 2f;
            dx += (cropLeft + cropSize / 2f) - vw / 2f;
            dy += (cropTop + cropSize / 2f) - vh / 2f;
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(dx, dy);
            baseScale = scale;
            minScale = coverScale;
            maxScale = scale * 5f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int vw = getWidth();
            int vh = getHeight();
            // 裁剪区背景保持深色，避免无图片时白屏
            canvas.drawColor(Color.BLACK);
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.drawBitmap(bitmap, matrix, bitmapPaint);
            }
            // 半透明黑色遮罩（四角，alpha 140）
            shapePaint.setStyle(Paint.Style.FILL);
            shapePaint.setColor(Color.argb(140, 0, 0, 0));
            canvas.drawRect(0, 0, vw, cropTop, shapePaint);
            canvas.drawRect(0, cropTop + cropSize, vw, vh, shapePaint);
            canvas.drawRect(0, cropTop, cropLeft, cropTop + cropSize, shapePaint);
            canvas.drawRect(cropLeft + cropSize, cropTop, vw, cropTop + cropSize, shapePaint);
            // 裁剪框边线（白色 2dp alpha 200）
            float density = getResources().getDisplayMetrics().density;
            shapePaint.setStyle(Paint.Style.STROKE);
            shapePaint.setStrokeWidth(2f * density);
            shapePaint.setColor(Color.argb(200, 255, 255, 255));
            canvas.drawRect(cropLeft, cropTop, cropLeft + cropSize, cropTop + cropSize, shapePaint);
            // 九宫格辅助线（白色 1dp alpha 80）
            shapePaint.setStrokeWidth(density);
            shapePaint.setColor(Color.argb(80, 255, 255, 255));
            float step = cropSize / 3f;
            for (int i = 1; i < 3; i++) {
                float x = cropLeft + step * i;
                canvas.drawLine(x, cropTop, x, cropTop + cropSize, shapePaint);
                float y = cropTop + step * i;
                canvas.drawLine(cropLeft, y, cropLeft + cropSize, y, shapePaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    isScaling = false;
                    lastX = event.getX();
                    lastY = event.getY();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        startDistance = spacing(event);
                        if (startDistance > 10f) {
                            isScaling = true;
                            startMatrix.set(matrix);
                        }
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isScaling && event.getPointerCount() >= 2) {
                        float newDist = spacing(event);
                        if (startDistance > 10f) {
                            float scaleFactor = newDist / startDistance;
                            // 把总缩放限制在 [minScale, maxScale]
                            float[] startVals = new float[9];
                            startMatrix.getValues(startVals);
                            float projected = startVals[Matrix.MSCALE_X] * scaleFactor;
                            if (projected < minScale) {
                                scaleFactor = minScale / startVals[Matrix.MSCALE_X];
                            } else if (projected > maxScale) {
                                scaleFactor = maxScale / startVals[Matrix.MSCALE_X];
                            }
                            matrix.set(startMatrix);
                            float midX = (event.getX(0) + event.getX(1)) / 2f;
                            float midY = (event.getY(0) + event.getY(1)) / 2f;
                            matrix.postScale(scaleFactor, scaleFactor, midX, midY);
                            clampTranslate();
                            invalidate();
                        }
                    } else if (!isScaling && event.getPointerCount() == 1) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        clampTranslate();
                        lastX = event.getX();
                        lastY = event.getY();
                        invalidate();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    isScaling = false;
                    int upIndex = event.getActionIndex();
                    int remainIndex = (upIndex == 0) ? 1 : 0;
                    lastX = event.getX(remainIndex);
                    lastY = event.getY(remainIndex);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isScaling = false;
                    break;
            }
            return true;
        }

        private float spacing(MotionEvent event) {
            float dx = event.getX(0) - event.getX(1);
            float dy = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        /** 约束矩阵：保证 bitmap 始终覆盖裁剪框（图片不能拖出裁剪框范围）。 */
        private void clampTranslate() {
            if (bitmap == null) return;
            float[] values = new float[9];
            matrix.getValues(values);
            float scale = values[Matrix.MSCALE_X];
            float transX = values[Matrix.MTRANS_X];
            float transY = values[Matrix.MTRANS_Y];
            float scaledBw = bitmap.getWidth() * scale;
            float scaledBh = bitmap.getHeight() * scale;
            if (scaledBw > cropSize) {
                float minX = cropLeft + cropSize - scaledBw;
                float maxX = cropLeft;
                if (transX < minX) transX = minX;
                if (transX > maxX) transX = maxX;
            } else {
                transX = cropLeft - (scaledBw - cropSize) / 2f;
            }
            if (scaledBh > cropSize) {
                float minY = cropTop + cropSize - scaledBh;
                float maxY = cropTop;
                if (transY < minY) transY = minY;
                if (transY > maxY) transY = maxY;
            } else {
                transY = cropTop - (scaledBh - cropSize) / 2f;
            }
            // 重建为纯 scale + translate，避免累计数值漂移
            matrix.setScale(scale, scale);
            matrix.postTranslate(transX, transY);
        }

        Bitmap getSourceBitmap() {
            return bitmap;
        }

        /** 把裁剪框内的图片区域裁成正方形 Bitmap，并按需降采样到 512px。 */
        Bitmap getCroppedBitmap() {
            if (bitmap == null || bitmap.isRecycled()) return null;
            float[] values = new float[9];
            matrix.getValues(values);
            float scale = values[Matrix.MSCALE_X];
            float transX = values[Matrix.MTRANS_X];
            float transY = values[Matrix.MTRANS_Y];
            // 裁剪框屏幕坐标 → source bitmap 坐标
            float srcX = (cropLeft - transX) / scale;
            float srcY = (cropTop - transY) / scale;
            float srcSize = cropSize / scale;
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            int sx = Math.round(srcX);
            int sy = Math.round(srcY);
            int ss = Math.round(srcSize);
            // 边界检查
            if (sx < 0) sx = 0;
            if (sy < 0) sy = 0;
            if (sx + ss > bw) ss = bw - sx;
            if (sy + ss > bh) ss = bh - sy;
            if (ss <= 0) return null;
            Bitmap cropped;
            try {
                cropped = Bitmap.createBitmap(bitmap, sx, sy, ss, ss);
            } catch (Throwable t) {
                return null;
            }
            if (cropped == null) return null;
            if (cropped.getWidth() > MAX_OUTPUT_SIZE) {
                Bitmap scaled = Bitmap.createScaledBitmap(cropped, MAX_OUTPUT_SIZE, MAX_OUTPUT_SIZE, true);
                if (scaled != cropped) cropped.recycle();
                cropped = scaled;
            }
            return cropped;
        }

        void release() {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
                bitmap = null;
            }
        }
    }
}
