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

import com.core.R;
import com.core.launcherbridge.LauncherMetadataBridge;
import com.core.metadata.MetadataController;
import com.core.metadata.VnMetadata;
import com.core.model.Game;

import java.util.List;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;

/** Launcher 风格的 VNDB 自定义关键词搜索与候选选择流程。 */
public final class LauncherCustomVndbSearchDialog {
    private LauncherCustomVndbSearchDialog() {
    }

    public static void show(Fragment fragment, Game game, Runnable onSaved) {
        if (fragment == null || game == null || !fragment.isAdded()) return;
        final String[] selectedSource = {MetadataController.SOURCE_VNDB};
        Dialog dialog = createDialog(fragment);
        LinearLayout root = createRoot(fragment);
        TextView titleView = title(fragment, sourceSearchTitle(fragment, selectedSource[0]));
        root.addView(titleView);

        // 数据源选择器
        LinearLayout sourceRow = new LinearLayout(fragment.requireContext());
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.setWeightSum(3f);
        String[] sources = {MetadataController.SOURCE_VNDB, MetadataController.SOURCE_BANGUMI, MetadataController.SOURCE_BANGUMI_MIRROR};
        String[] sourceLabels = {
                "VNDB",
                "Bangumi",
                fragment.getString(R.string.settings_bangumi_mirror)
        };
        TextView[] sourceChips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView chip = new TextView(fragment.requireContext());
            chip.setText(sourceLabels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(12);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setPadding(LauncherTheme.dp(fragment.requireContext(), 10), LauncherTheme.dp(fragment.requireContext(), 7), LauncherTheme.dp(fragment.requireContext(), 10), LauncherTheme.dp(fragment.requireContext(), 7));
            LauncherTheme.chip(chip, sources[i].equals(selectedSource[0]));
            chip.setOnClickListener(v -> {
                selectedSource[0] = sources[idx];
                titleView.setText(sourceSearchTitle(fragment, selectedSource[0]));
                for (int j = 0; j < 3; j++) LauncherTheme.chip(sourceChips[j], j == idx);
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) chipParams.setMarginEnd(LauncherTheme.dp(fragment.requireContext(), 6));
            sourceRow.addView(chip, chipParams);
            sourceChips[i] = chip;
        }
        LinearLayout.LayoutParams sourceRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sourceRowParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0);
        root.addView(sourceRow, sourceRowParams);

        TextView info = info(fragment,
                fragment.getString(R.string.settings_custom_search_summary));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0);
        root.addView(info, infoParams);

        TextView label = label(fragment,
                fragment.getString(R.string.settings_search_keywords));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0);
        root.addView(label, labelParams);

        EditText input = new com.apps.widget.LauncherEditText(fragment.requireContext());
        input.setSingleLine(true);
        input.setText(safe(game.title));
        input.setSelectAllOnFocus(true);
        input.setHint(R.string.settings_search_keywords_hint);
        input.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_color));
        input.setHintTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_input_hint_color));
        input.setTextSize(13);
        input.setBackground(LauncherTheme.cancelChip(fragment.requireContext()));
        LauncherTheme.styleTextInput(input);
        input.setPadding(LauncherTheme.dp(fragment.requireContext(), 13), LauncherTheme.dp(fragment.requireContext(), 9), LauncherTheme.dp(fragment.requireContext(), 13), LauncherTheme.dp(fragment.requireContext(), 9));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 5), 0, 0);
        root.addView(input, inputParams);

        TextView hint = hint(fragment,
                fragment.getString(R.string.settings_search_keywords_description));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 7), 0, 0);
        root.addView(hint, hintParams);

        LinearLayout btnRow = new LinearLayout(fragment.requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(2f);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0);
        btnRow.setLayoutParams(btnRowParams);

        TextView cancel = button(fragment,
                fragment.getString(R.string.settings_cancel), false);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, LauncherTheme.dp(fragment.requireContext(), 38), 1f);
        cancelParams.setMargins(0, 0, LauncherTheme.dp(fragment.requireContext(), 5), 0);
        cancel.setLayoutParams(cancelParams);
        cancel.setOnClickListener(view -> dialog.dismiss());
        btnRow.addView(cancel);

        TextView search = button(fragment,
                fragment.getString(R.string.settings_search), true);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, LauncherTheme.dp(fragment.requireContext(), 38), 1f);
        searchParams.setMargins(LauncherTheme.dp(fragment.requireContext(), 5), 0, 0, 0);
        search.setLayoutParams(searchParams);
        search.setOnClickListener(view -> {
            String keyword = input.getText() == null ? "" : input.getText().toString().trim();
            if (keyword.isEmpty()) {
                Toast.makeText(fragment.requireContext(),
                        R.string.settings_enter_search_keywords, Toast.LENGTH_SHORT).show();
                return;
            }
            search.setEnabled(false);
            search.setText(R.string.settings_searching);
            String src = selectedSource[0];
            LauncherMetadataBridge.CandidatesCallback cb = (candidates, error) -> {
                if (!fragment.isAdded()) return;
                dialog.dismiss();
                if (error != null) {
                    Toast.makeText(fragment.requireContext(),
                            fragment.getString(R.string.settings_source_search_failed,
                                    sourceLabel(fragment, src), error), Toast.LENGTH_LONG).show();
                    return;
                }
                if (candidates == null || candidates.isEmpty()) {
                    Toast.makeText(fragment.requireContext(),
                            fragment.getString(R.string.settings_no_source_results,
                                    sourceLabel(fragment, src)), Toast.LENGTH_SHORT).show();
                    return;
                }
                showCandidates(fragment, game, candidates, onSaved, src);
            };
            if (MetadataController.SOURCE_VNDB.equals(src)) {
                LauncherMetadataBridge.searchVndbCandidatesAsync(fragment.requireContext(), keyword, 8, cb);
            } else {
                LauncherMetadataBridge.searchBangumiCandidatesAsync(fragment.requireContext(), keyword, 8, cb);
            }
        });
        btnRow.addView(search);
        root.addView(btnRow);
        setContent(dialog, root, fragment, 288);
        focusAndShowKeyboard(dialog, input, fragment);
    }

    private static String sourceLabel(Fragment fragment, String source) {
        if (MetadataController.SOURCE_BANGUMI.equals(source)) return "Bangumi";
        if (MetadataController.SOURCE_BANGUMI_MIRROR.equals(source)) {
            return fragment.getString(R.string.settings_bangumi_mirror);
        }
        return "VNDB";
    }

    private static String sourceSearchTitle(Fragment fragment, String source) {
        return fragment.getString(R.string.settings_custom_search_title,
                sourceLabel(fragment, source));
    }

    private static void showCandidates(Fragment fragment, Game game, List<VnMetadata> candidates,
                                       Runnable onSaved, String source) {
        String label = sourceLabel(fragment, source);
        Dialog dialog = createDialog(fragment);
        LinearLayout root = createRoot(fragment);
        root.addView(title(fragment,
                fragment.getString(R.string.settings_choose_source_result, label)));

        TextView info = info(fragment,
                fragment.getString(R.string.settings_choose_source_result_summary, label));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0);
        root.addView(info, infoParams);

        LinearLayout list = new LinearLayout(fragment.requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        for (VnMetadata metadata : candidates) {
            if (metadata == null) continue;
            TextView row = new TextView(fragment.requireContext());
            String displayTitle = first(metadata.chineseTitle, metadata.romanTitle,
                    fragment.getString(R.string.settings_unnamed));
            String original = first(metadata.originalTitle, metadata.id, "");
            String developer = first(metadata.developer,
                    fragment.getString(R.string.settings_source_candidate, label));
            row.setText(displayTitle + "\n" + original + "\n" + developer);
            row.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.launcher_text_color));
            row.setTextSize(12);
            row.setLineSpacing(LauncherTheme.dp(fragment.requireContext(), 4), 1f);
            row.setPadding(LauncherTheme.dp(fragment.requireContext(), 12), LauncherTheme.dp(fragment.requireContext(), 9), LauncherTheme.dp(fragment.requireContext(), 12), LauncherTheme.dp(fragment.requireContext(), 9));
            row.setBackground(LauncherTheme.cancelChip(fragment.requireContext()));
            row.setOnClickListener(view -> {
                row.setEnabled(false);
                LauncherMetadataBridge.Callback saveCb = success -> {
                    if (!fragment.isAdded()) return;
                    dialog.dismiss();
                    Toast.makeText(fragment.requireContext(),
                            fragment.getString(success
                                            ? R.string.settings_metadata_bound
                                            : R.string.settings_metadata_save_failed,
                                    label),
                            Toast.LENGTH_SHORT).show();
                    if (success && onSaved != null) onSaved.run();
                };
                if (MetadataController.SOURCE_VNDB.equals(source)) {
                    LauncherMetadataBridge.saveSelectedVndbMetadataAsync(fragment.requireContext(), game, metadata, saveCb);
                } else {
                    LauncherMetadataBridge.saveSelectedBangumiMetadataAsync(fragment.requireContext(), game, metadata, saveCb);
                }
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 9), 0, 0);
            list.addView(row, rowParams);
        }

        ScrollView scroll = new ScrollView(fragment.requireContext());
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 4), 0, 0);
        root.addView(scroll, scrollParams);

        TextView cancel = button(fragment,
                fragment.getString(R.string.settings_cancel), false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(fragment.requireContext(), 38));
        cancelParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0);
        root.addView(cancel, cancelParams);
        setContent(dialog, root, fragment, 288);
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(LauncherTheme.dp(fragment.requireContext(), 288),
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
        root.setPadding(LauncherTheme.dp(fragment.requireContext(), 22), LauncherTheme.dp(fragment.requireContext(), 18), LauncherTheme.dp(fragment.requireContext(), 22), LauncherTheme.dp(fragment.requireContext(), 15));
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
        info.setLineSpacing(LauncherTheme.dp(fragment.requireContext(), 4), 1f);
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
        window.setLayout(LauncherTheme.dp(fragment.requireContext(), widthDp), WindowManager.LayoutParams.WRAP_CONTENT);
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
}
