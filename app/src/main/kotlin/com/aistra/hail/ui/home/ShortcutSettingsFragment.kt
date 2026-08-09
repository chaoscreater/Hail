package com.aistra.hail.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.marginRelative
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.NameComparator

/**
 * Lists every checked app that has custom add-to-home-screen settings applied — a prerequisite
 * app, or the Bluetooth/location toggles — regardless of which tag category it lives under.
 * Tap/long-press behave identically to a Home tag page via the shared [AppContextActions].
 */
class ShortcutSettingsFragment : MainFragment(), PagerAdapter.OnItemClickListener,
    PagerAdapter.OnItemLongClickListener {

    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private val actions by lazy { AppContextActions(this, onListChanged = { updateList() }) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(emptyList()).apply {
            onItemClickListener = this@ShortcutSettingsFragment
            onItemLongClickListener = this@ShortcutSettingsFragment
        }
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
        }
        binding.refresh.apply {
            setOnRefreshListener {
                updateList()
                binding.refresh.isRefreshing = false
            }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateList()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
    }

    private fun updateList() {
        val list = HailData.checkedList.filter {
            !it.prereqPackage.isNullOrEmpty() || it.enableBluetooth || it.enableLocation
        }.filter {
            HailData.showUninstalled || it.applicationInfo != null
        }.sortedWith(NameComparator)
        binding.empty.isVisible = list.isEmpty()
        pagerAdapter.submitList(list)
    }

    override fun onItemClick(info: AppInfo) = actions.onItemClick(info)
    override fun onItemLongClick(info: AppInfo): Boolean = actions.onItemLongClick(info)

    override fun onDestroyView() {
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
