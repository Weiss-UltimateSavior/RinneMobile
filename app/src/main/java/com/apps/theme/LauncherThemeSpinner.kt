package com.apps.theme

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.NonNull
import com.core.R

/** Spinner 下拉框样式与主题适配器。 */
internal object LauncherThemeSpinner {

    internal fun styleSpinner(spinner: Spinner?) {
        if (spinner == null) return
        val context = spinner.context
        spinner.background = LauncherThemeDrawables.secondaryButton(context, 20f)
        // dropdown 容器使用与弹窗一致的圆角背景
        spinner.setPopupBackgroundResource(R.drawable.launcher_spinner_popup_bg)
    }

    internal fun <T> spinnerAdapter(context: Context, items: Array<T>): ArrayAdapter<T> {
        return object : ArrayAdapter<T>(context, R.layout.spinner_item_themed, items) {
            @NonNull
            override fun getView(position: Int, convertView: View?, @NonNull parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                LauncherThemeParts.styleSpinnerItemView(view, false)
                return view
            }

            @NonNull
            override fun getDropDownView(position: Int, convertView: View?, @NonNull parent: ViewGroup): View {
                val view = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.spinner_dropdown_themed, parent, false)
                if (view is TextView) {
                    // Spinner 空项兜底显示空串，避免向用户暴露 "null"
                    view.text = getItem(position)?.toString() ?: ""
                }
                LauncherThemeParts.styleSpinnerItemView(view, true)
                return view
            }
        }
    }
}
