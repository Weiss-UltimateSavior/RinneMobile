package com.apps.home

import androidx.fragment.app.Fragment

/** Single mapping from a persisted [HomeStyle] to its concrete home Fragment. */
object LauncherHomeFragmentFactory {
    private data class FragmentSpec(
        val fragmentClass: Class<out Fragment>,
        val create: () -> Fragment,
    )

    private fun spec(style: HomeStyle): FragmentSpec = when (style) {
        HomeStyle.DEFAULT -> FragmentSpec(LauncherHomeFragment::class.java, ::LauncherHomeFragment)
        HomeStyle.FEATURED -> FragmentSpec(
            LauncherFeaturedHomeFragment::class.java,
            ::LauncherFeaturedHomeFragment,
        )
    }

    fun create(style: HomeStyle): Fragment = spec(style).create()

    fun matches(fragment: Fragment?, style: HomeStyle): Boolean =
        fragment?.javaClass == spec(style).fragmentClass
}
