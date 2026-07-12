package com.apps.settings;

import android.app.Dialog;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherMetadataBridge;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.model.Game;

import java.util.List;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;

/** Launcher 风格的 VNDB 自定义关键词搜索与候选选择流程。 */
public final class LauncherCustomVndbSearchDialog {
    private LauncherCustomVndbSearchDialog() {
    }

    public static void show(Fragment fragment, Game game, Runnable onSaved) {
        if (fragment == null || game == null || !fragment.isAdded()) return;
        Dialog dialog = createDialog(fragment);
        LinearLayout root = createRoot(fragment);
        root.addView(title(fragment, "自定义搜索 VNDB"));

        TextView info = info(fragment, "使用自定义关键词在 VNDB 搜索，并从候选结果中选择要绑定的元数据。");
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.setMargins(0, dp(fragment, 13), 0, 0);
        root.addView(info, infoParams);

        TextView label = label(fragment, "搜索关键词");
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(fragment, 13), 0, 0);
        root.addView(label, labelParams);

        EditText input = new EditText(fragment.requireContext());
        input.setSingleLine(true);
        input.setText(safe(game.title));
        input.setSelectAllOnFocus(true);
        input.setHint("输入 VNDB 搜索关键词或原名");
        input.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_color));
        input.setHintTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_input_hint_color));
        input.setTextSize(13);
        input.setBackground(LauncherTheme.cancelChip(fragment.requireContext()));
        input.setPadding(dp(fragment, 13), dp(fragment, 9), dp(fragment, 13), dp(fragment, 9));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, dp(fragment, 5), 0, 0);
        root.addView(input, inputParams);

        TextView hint = hint(fragment, "默认填入当前游戏标题，可改成原名、别名或 VNDB 关键词");
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.setMargins(0, dp(fragment, 7), 0, 0);
        root.addView(hint, hintParams);

        LinearLayout btnRow = new LinearLayout(fragment.requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(2f);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowParams.setMargins(0, dp(fragment, 13), 0, 0);
        btnRow.setLayoutParams(btnRowParams);

        TextView cancel = button(fragment, "取消", false);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(fragment, 38), 1f);
        cancelParams.setMargins(0, 0, dp(fragment, 5), 0);
        cancel.setLayoutParams(cancelParams);
        cancel.setOnClickListener(view -> dialog.dismiss());
        btnRow.addView(cancel);

        TextView search = button(fragment, "搜索", true);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(fragment, 38), 1f);
        searchParams.setMargins(dp(fragment, 5), 0, 0, 0);
        search.setLayoutParams(searchParams);
        search.setOnClickListener(view -> {
            String keyword = input.getText() == null ? "" : input.getText().toString().trim();
            if (keyword.isEmpty()) {
                Toast.makeText(fragment.requireContext(), "请输入搜索关键词", Toast.LENGTH_SHORT).show();
                return;
            }
            search.setEnabled(false);
            search.setText("正在搜索...");
            LauncherMetadataBridge.searchVndbCandidatesAsync(fragment.requireContext(), keyword, 8,
                    (candidates, error) -> {
                        if (!fragment.isAdded()) return;
                        dialog.dismiss();
                        if (error != null) {
                            Toast.makeText(fragment.requireContext(), "VNDB 搜索失败：" + error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (candidates == null || candidates.isEmpty()) {
                            Toast.makeText(fragment.requireContext(), "没有匹配到 VNDB 结果", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showCandidates(fragment, game, candidates, onSaved);
                    });
        });
        btnRow.addView(search);
        root.addView(btnRow);
        setContent(dialog, root, fragment, 288);
        focusAndShowKeyboard(dialog, input, fragment);
    }

    private static void showCandidates(Fragment fragment, Game game, List<VnMetadata> candidates,
                                       Runnable onSaved) {
        Dialog dialog = createDialog(fragment);
        LinearLayout root = createRoot(fragment);
        root.addView(title(fragment, "选择 VNDB 匹配结果"));

        TextView info = info(fragment, "点选一个候选项后会保存 VNDB 元数据绑定。封面同步仍使用更多选项里的同步功能。");
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.setMargins(0, dp(fragment, 13), 0, 0);
        root.addView(info, infoParams);

        LinearLayout list = new LinearLayout(fragment.requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        for (VnMetadata metadata : candidates) {
            if (metadata == null) continue;
            TextView row = new TextView(fragment.requireContext());
            String displayTitle = first(metadata.chineseTitle, metadata.romanTitle, "未命名");
            String original = first(metadata.originalTitle, metadata.id, "");
            String developer = first(metadata.developer, "VNDB 候选");
            row.setText(displayTitle + "\n" + original + "\n" + developer);
            row.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_color));
            row.setTextSize(12);
            row.setLineSpacing(dp(fragment, 4), 1f);
            row.setPadding(dp(fragment, 12), dp(fragment, 9), dp(fragment, 12), dp(fragment, 9));
            row.setBackground(LauncherTheme.cancelChip(fragment.requireContext()));
            row.setOnClickListener(view -> {
                row.setEnabled(false);
                LauncherMetadataBridge.saveSelectedVndbMetadataAsync(fragment.requireContext(), game, metadata,
                        success -> {
                            if (!fragment.isAdded()) return;
                            dialog.dismiss();
                            Toast.makeText(fragment.requireContext(),
                                    success ? "VNDB 元数据已绑定" : "VNDB 元数据保存失败", Toast.LENGTH_SHORT).show();
                            if (success && onSaved != null) onSaved.run();
                        });
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, dp(fragment, 9), 0, 0);
            list.addView(row, rowParams);
        }

        ScrollView scroll = new ScrollView(fragment.requireContext());
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, dp(fragment, 4), 0, 0);
        root.addView(scroll, scrollParams);

        TextView cancel = button(fragment, "取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(fragment, 38));
        cancelParams.setMargins(0, dp(fragment, 13), 0, 0);
        root.addView(cancel, cancelParams);
        setContent(dialog, root, fragment, 288);
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(dp(fragment, 288),
                (int) (fragment.getResources().getDisplayMetrics().heightPixels * 0.72f));
    }

    private static Dialog createDialog(Fragment fragment) {
        Dialog dialog = new Dialog(fragment.requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    private static LinearLayout createRoot(Fragment fragment) {
        LinearLayout root = new LinearLayout(fragment.requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(fragment, 22), dp(fragment, 18), dp(fragment, 22), dp(fragment, 15));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        return root;
    }

    private static TextView title(Fragment fragment, String text) {
        TextView title = new TextView(fragment.requireContext());
        title.setText(text);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        return title;
    }

    private static TextView info(Fragment fragment, String text) {
        TextView info = new TextView(fragment.requireContext());
        info.setText(text);
        info.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_muted_color));
        info.setTextSize(12);
        info.setLineSpacing(dp(fragment, 4), 1f);
        return info;
    }

    private static TextView label(Fragment fragment, String text) {
        TextView label = new TextView(fragment.requireContext());
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_color));
        label.setTextSize(12);
        label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    private static TextView hint(Fragment fragment, String text) {
        TextView hint = new TextView(fragment.requireContext());
        hint.setText(text);
        hint.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_muted_color));
        hint.setTextSize(11);
        return hint;
    }

    private static TextView button(Fragment fragment, String text, boolean primary) {
        TextView button = new TextView(fragment.requireContext());
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(13);
        button.setTypeface(null, Typeface.BOLD);
        if (primary) LauncherTheme.primaryButton(button); else LauncherTheme.secondaryButton(button);
        return button;
    }

    private static void setContent(Dialog dialog, LinearLayout root, Fragment fragment, int widthDp) {
        dialog.setContentView(root);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setLayout(dp(fragment, widthDp), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void focusAndShowKeyboard(Dialog dialog, EditText input, Fragment fragment) {
        input.setFocusableInTouchMode(true);
        input.requestFocus();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.setOnDismissListener(d -> hideKeyboard(input, fragment));
        input.post(() -> showKeyboard(input, fragment, InputMethodManager.SHOW_IMPLICIT));
        input.postDelayed(() -> showKeyboard(input, fragment, InputMethodManager.SHOW_FORCED), 180);
    }

    private static void showKeyboard(EditText input, Fragment fragment, int flags) {
        if (!fragment.isAdded()) return;
        InputMethodManager manager = (InputMethodManager) fragment.requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(input, flags);
    }

    private static void hideKeyboard(EditText input, Fragment fragment) {
        if (!fragment.isAdded()) return;
        InputMethodManager manager = (InputMethodManager) fragment.requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private static int dp(Fragment fragment, int value) {
        return (int) (value * fragment.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
}
