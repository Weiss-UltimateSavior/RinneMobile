package com.apps.game;

import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.yuki.yukihub.R;
import com.yuki.yukihub.util.AppExecutors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared searchable package picker used by the add-game and edit-game forms. */
final class LauncherAppPickerDialog {
    interface Callback {
        void onPackageSelected(String packageName);
    }

    private LauncherAppPickerDialog() {
    }

    static void show(AppCompatActivity activity, Callback callback) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_launcher_app_picker);
        LauncherTheme.applyPrimaryTone(dialog.findViewById(android.R.id.content));
        RecyclerView list = dialog.findViewById(R.id.recyclerLauncherAppPicker);
        View loading = dialog.findViewById(R.id.layoutLauncherAppLoading);
        TextView hint = dialog.findViewById(R.id.tvLauncherAppPickerHint);
        EditText search = dialog.findViewById(R.id.etLauncherAppSearch);
        TextView close = dialog.findViewById(R.id.btnCloseLauncherAppPicker);
        LauncherTheme.secondaryButton(close);
        list.setLayoutManager(new LinearLayoutManager(activity));
        close.setOnClickListener(view -> dialog.dismiss());
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.74f),
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f));
        }

        AppExecutors.runOnIo(() -> {
            List<Item> items = loadLaunchableApps(activity);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                loading.setVisibility(View.GONE);
                list.setVisibility(View.VISIBLE);
                if (items.isEmpty()) {
                    hint.setText("没有找到可启动的应用");
                    return;
                }
                hint.setText("共 " + items.size() + " 个可启动应用，可搜索应用名或包名");
                Adapter adapter = new Adapter(items, item -> {
                    if (callback != null) callback.onPackageSelected(item.packageName);
                    dialog.dismiss();
                });
                list.setAdapter(adapter);
                search.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        adapter.filter(s == null ? "" : s.toString());
                        hint.setText("共 " + items.size() + " 个应用，当前显示 " + adapter.getItemCount() + " 个");
                    }
                    @Override public void afterTextChanged(android.text.Editable s) { }
                });
            });
        });
    }

    private static List<Item> loadLaunchableApps(AppCompatActivity activity) {
        Map<String, Item> itemsByPackage = new LinkedHashMap<>();
        try {
            PackageManager packageManager = activity.getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchers = packageManager.queryIntentActivities(launcherIntent, 0);
            if (launchers != null) {
                for (ResolveInfo resolveInfo : launchers) {
                    if (resolveInfo != null && resolveInfo.activityInfo != null) {
                        addItem(itemsByPackage, packageManager, resolveInfo.activityInfo.applicationInfo);
                    }
                }
            }
            List<ApplicationInfo> apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            if (apps != null) {
                for (ApplicationInfo app : apps) {
                    if (app != null && app.packageName != null
                            && packageManager.getLaunchIntentForPackage(app.packageName) != null) {
                        addItem(itemsByPackage, packageManager, app);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<Item> items = new ArrayList<>(itemsByPackage.values());
        items.sort((left, right) -> left.label.compareToIgnoreCase(right.label));
        return items;
    }

    private static void addItem(Map<String, Item> itemsByPackage, PackageManager packageManager,
                                ApplicationInfo app) {
        if (app == null || app.packageName == null || itemsByPackage.containsKey(app.packageName)) return;
        try {
            CharSequence label = packageManager.getApplicationLabel(app);
            itemsByPackage.put(app.packageName, new Item(label == null ? app.packageName : label.toString(),
                    app.packageName, packageManager.getApplicationIcon(app)));
        } catch (Throwable ignored) {
        }
    }

    private static final class Item {
        final String label;
        final String packageName;
        final Drawable icon;

        Item(String label, String packageName, Drawable icon) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.icon = icon;
        }
    }

    private interface ItemCallback {
        void onPick(Item item);
    }

    private static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<Item> allItems;
        private final List<Item> items = new ArrayList<>();
        private final ItemCallback callback;

        Adapter(List<Item> items, ItemCallback callback) {
            this.allItems = items == null ? new ArrayList<>() : new ArrayList<>(items);
            this.items.addAll(this.allItems);
            this.callback = callback;
        }

        void filter(String query) {
            String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            items.clear();
            for (Item item : allItems) {
                if (normalized.isEmpty() || item.label.toLowerCase(Locale.ROOT).contains(normalized)
                        || item.packageName.toLowerCase(Locale.ROOT).contains(normalized)) {
                    items.add(item);
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_app_picker, parent, false);
            LauncherTabletPortraitScaler.apply(view);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    Math.round(68 * parent.getResources().getDisplayMetrics().density * LauncherTabletPortraitScaler.scaleFor(view)));
            params.setMargins(0, 0, 0, (int) (7 * parent.getResources().getDisplayMetrics().density));
            view.setLayoutParams(params);
            return new Holder(view);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Item item = items.get(position);
            holder.label.setText(item.label.isEmpty() ? item.packageName : item.label);
            holder.packageName.setText(item.packageName);
            holder.icon.setImageDrawable(item.icon == null ? null : item.icon);
            if (item.icon == null) holder.icon.setImageResource(android.R.mipmap.sym_def_app_icon);
            holder.itemView.setOnClickListener(view -> {
                if (callback != null) callback.onPick(item);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView packageName;

            Holder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.ivLauncherAppIcon);
                label = itemView.findViewById(R.id.tvLauncherAppLabel);
                packageName = itemView.findViewById(R.id.tvLauncherAppPackage);
            }
        }
    }
}
