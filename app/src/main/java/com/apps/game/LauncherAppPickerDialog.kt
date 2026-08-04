package com.apps.game

import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.util.AppExecutors
import java.util.Locale

/** Shared searchable package picker used by the add-game and edit-game forms. */
internal object LauncherAppPickerDialog {
    private const val TAG = "LauncherAppPickerDialog"

    fun interface Callback {
        fun onPackageSelected(packageName: String)
    }

    @JvmStatic
    fun show(activity: AppCompatActivity, callback: Callback) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_launcher_app_picker)
        LauncherTheme.applyPrimaryTone(dialog.findViewById(android.R.id.content))
        val list: RecyclerView = dialog.findViewById(R.id.recyclerLauncherAppPicker)
        val loading: View = dialog.findViewById(R.id.layoutLauncherAppLoading)
        val hint: TextView = dialog.findViewById(R.id.tvLauncherAppPickerHint)
        val search: EditText = dialog.findViewById(R.id.etLauncherAppSearch)
        val close: TextView = dialog.findViewById(R.id.btnCloseLauncherAppPicker)
        LauncherTheme.secondaryButton(close)
        list.layoutManager = LinearLayoutManager(activity)
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.74f).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()
        )

        AppExecutors.runOnIo {
            val items = loadLaunchableApps(activity)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!dialog.isShowing) return@runOnUiThread
                loading.visibility = View.GONE
                list.visibility = View.VISIBLE
                if (items.isEmpty()) {
                    hint.setText(R.string.game_app_picker_empty)
                    return@runOnUiThread
                }
                hint.setText(activity.getString(R.string.game_app_picker_count, items.size))
                val adapter = Adapter(items) { item ->
                    callback.onPackageSelected(item.packageName)
                    dialog.dismiss()
                }
                list.adapter = adapter
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        adapter.filter(if (s == null) "" else s.toString())
                        hint.setText(activity.getString(R.string.game_app_picker_filtered_count,
                            items.size, adapter.itemCount))
                    }
                    override fun afterTextChanged(s: Editable?) { }
                })
            }
        }
    }

    private fun loadLaunchableApps(activity: AppCompatActivity): List<Item> {
        val itemsByPackage = LinkedHashMap<String, Item>()
        try {
            val packageManager = activity.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchers = packageManager.queryIntentActivities(launcherIntent, 0)
            if (launchers != null) {
                for (resolveInfo in launchers) {
                    if (resolveInfo != null && resolveInfo.activityInfo != null) {
                        addItem(itemsByPackage, packageManager, resolveInfo.activityInfo.applicationInfo)
                    }
                }
            }
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            if (apps != null) {
                for (app in apps) {
                    if (app != null && app.packageName != null
                        && packageManager.getLaunchIntentForPackage(app.packageName) != null
                    ) {
                        addItem(itemsByPackage, packageManager, app)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PackageManager query failed", e)
        }
        val items = ArrayList(itemsByPackage.values)
        items.sortWith(Comparator { left, right -> left.label.compareTo(right.label, ignoreCase = true) })
        return items
    }

    private fun addItem(itemsByPackage: MutableMap<String, Item>, packageManager: PackageManager,
                        app: ApplicationInfo) {
        if (app == null || app.packageName == null || itemsByPackage.containsKey(app.packageName)) return
        try {
            val label = packageManager.getApplicationLabel(app)
            itemsByPackage[app.packageName] = Item(if (label == null) app.packageName else label.toString(),
                app.packageName, packageManager.getApplicationIcon(app))
        } catch (e: Exception) {
            Log.w(TAG, "PackageManager query failed", e)
        }
    }

    private data class Item(val label: String, val packageName: String, val icon: Drawable?)

    private class Adapter(
        all: List<Item>,
        private val callback: (Item) -> Unit
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        private val allItems: MutableList<Item> = ArrayList(all)
        private val items: MutableList<Item> = ArrayList(all)

        fun filter(query: String) {
            val normalized = query.trim().lowercase(Locale.ROOT)
            items.clear()
            for (item in allItems) {
                if (normalized.isEmpty() || item.label.lowercase(Locale.ROOT).contains(normalized)
                    || item.packageName.lowercase(Locale.ROOT).contains(normalized)
                ) {
                    items.add(item)
                }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_launcher_app_picker, parent, false)
            LauncherTabletPortraitScaler.apply(view)
            val params = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                Math.round(68 * parent.resources.displayMetrics.density * LauncherTabletPortraitScaler.scaleFor(view)))
            params.setMargins(0, 0, 0, (7 * parent.resources.displayMetrics.density).toInt())
            view.layoutParams = params
            return Holder(view, callback)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.currentItem = item
            holder.label.text = if (item.label.isEmpty()) item.packageName else item.label
            holder.packageName.text = item.packageName
            holder.icon.setImageDrawable(item.icon)
            if (item.icon == null) holder.icon.setImageResource(android.R.mipmap.sym_def_app_icon)
        }

        override fun getItemCount(): Int = items.size

        class Holder(
            itemView: View,
            private val callback: (Item) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.ivLauncherAppIcon)
            val label: TextView = itemView.findViewById(R.id.tvLauncherAppLabel)
            val packageName: TextView = itemView.findViewById(R.id.tvLauncherAppPackage)
            var currentItem: Item? = null

            init {
                itemView.setOnClickListener { currentItem?.let(callback) }
            }
        }
    }
}
