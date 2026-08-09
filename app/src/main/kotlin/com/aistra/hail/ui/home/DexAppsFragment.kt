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
 * A user-curated list of "Dex apps" — apps flagged via the fab's "Dex app shortcuts" picker
 * (same UI as Home screen shortcuts, just backed by [AppInfo.dexApp] instead of
 * [AppInfo.addToHomeScreen]). Membership here is independent of tag categories: an app can
 * appear both under its tag in Apps and here. Tap/long-press behave identically to a Home tag
 * page via the shared [AppContextActions].
 */
class DexAppsFragment : MainFragment(), PagerAdapter.OnItemClickListener,
    PagerAdapter.OnItemLongClickListener {

    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private val actions by lazy { AppContextActions(this, onListChanged = { updateList() }) }
    private val pinShortcutsDialog by lazy {
        PinShortcutsDialogController(
            fragment = this,
            titleRes = R.string.dex_app_shortcuts,
            getFlag = { it.dexApp },
            setFlag = { info, value -> info.dexApp = value },
            onSelectionChanged = { updateList() }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(emptyList()).apply {
            onItemClickListener = this@DexAppsFragment
            onItemLongClickListener = this@DexAppsFragment
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
        activity.fabDexApps.setOnClickListener { pinShortcutsDialog.show() }
        pinShortcutsDialog.onResume()
    }

    private fun updateList() {
        val list = HailData.checkedList.filter { it.dexApp }.filter {
            HailData.showUninstalled || it.applicationInfo != null
        }.sortedWith(NameComparator)
        binding.empty.isVisible = list.isEmpty()
        pagerAdapter.submitList(list)
    }

    override fun onItemClick(info: AppInfo) = actions.onItemClick(info)
    override fun onItemLongClick(info: AppInfo): Boolean = actions.onItemLongClick(info)

    override fun onDestroyView() {
        pagerAdapter.onDestroy()
        pinShortcutsDialog.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
