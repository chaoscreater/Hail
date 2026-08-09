package com.aistra.hail.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.app.HailData.tags
import com.aistra.hail.databinding.FragmentHomeBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class HomeFragment : MainFragment() {

    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()

    private val pinShortcutsDialog by lazy {
        PinShortcutsDialogController(
            fragment = this,
            titleRes = R.string.home_shortcuts,
            getFlag = { it.addToHomeScreen },
            setFlag = { info, value -> info.addToHomeScreen = value }
        )
    }

    private var _binding: FragmentHomeBinding? = null
    val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        if (tags.size == 1) binding.tabs.isVisible = false
        binding.pager.adapter = HomeAdapter(this)
        // Keep all tag fragments alive so revisiting a tab requires no RecyclerView
        // rebind — DiffUtil sees no changes and the icons render from existing views instantly.
        binding.pager.offscreenPageLimit = tags.size.coerceAtLeast(1)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = tags[position].first
        }.attach()
        binding.tabs.applyDefaultInsetter { paddingRelative(isRtl, start = !activity.isLandscape, end = true) }

        // For tabs more than 1 position away, jump instantly so the scroll animation
        // doesn't sweep visually through every intermediate page's icons.
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (kotlin.math.abs(tab.position - binding.pager.currentItem) > 1) {
                    binding.pager.setCurrentItem(tab.position, false)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // When a page fully settles, refresh that page's fragment with the now-correct
        // tab position. This fixes the race where onResume() fires during animation
        // before TabLayoutMediator has updated selectedTabPosition.
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Load icons immediately when a tab is tapped, without waiting for the
                // scroll animation to finish. This makes distant tabs feel responsive.
                (childFragmentManager.findFragmentByTag("f$position") as? PagerFragment)
                    ?.updateCurrentList()
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val pos = binding.pager.currentItem
                    (childFragmentManager.findFragmentByTag("f$pos") as? PagerFragment)
                        ?.updateCurrentList()
                }
            }
        })

        activity.fabHome.setOnClickListener { binding.pager.setCurrentItem(0, false) }
        activity.fabPinShortcuts.setOnClickListener { pinShortcutsDialog.show() }

        // Pre-warm the icon cache for all checked apps so switching tag categories
        // shows icons instantly instead of waiting for them to load on demand.
        val appsToPreload = HailData.checkedList.mapNotNull { it.applicationInfo }
        AppIconCache.preloadIconsAsync(requireContext().applicationContext, appsToPreload, myUserId)

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        pinShortcutsDialog.onResume()
    }

    override fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        pinShortcutsDialog.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
