package com.apps.home

import androidx.annotation.StringRes
import com.core.R

/** Launcher portrait home styles persisted by their stable storage value. */
enum class HomeStyle(
    val storageValue: String,
    @StringRes val labelResId: Int,
) {
    DEFAULT("default", R.string.app_home_style_default),
    FEATURED("featured", R.string.app_home_style_featured),
    ;

    companion object {
        @JvmStatic
        fun fromStorage(value: String?): HomeStyle =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
