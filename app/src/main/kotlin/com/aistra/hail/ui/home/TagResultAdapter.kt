package com.aistra.hail.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R

/**
 * Renders [HailData.tags][com.aistra.hail.app.HailData.tags] entries that match a Home search
 * query as "<name> (category)" rows — a smaller, horizontal layout ([R.layout.item_tag_result],
 * [R.dimen.widget_icon_size] icon) rather than [PagerAdapter]'s full-size app-icon grid tile, so a
 * single full-width result row doesn't look oversized next to the app grid below it. Kept
 * separate from [PagerAdapter] (whose item type stays [AppInfo][com.aistra.hail.app.AppInfo],
 * shared verbatim by [ShortcutSettingsFragment]/[DexAppsFragment]) and stitched together with it
 * via a `ConcatAdapter` in [PagerFragment] only.
 */
class TagResultAdapter(private val onClick: (Pair<String, Int>) -> Unit) :
    ListAdapter<Pair<String, Int>, TagResultAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_tag_result, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = getItem(position)
        holder.itemView.run {
            findViewById<ImageView>(R.id.app_icon).setImageResource(R.drawable.ic_outline_category)
            findViewById<TextView>(R.id.app_name).text = context.getString(R.string.tab_category_search_result, tag.first)
            setOnClickListener { onClick(tag) }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private object Diff : DiffUtil.ItemCallback<Pair<String, Int>>() {
        override fun areItemsTheSame(oldItem: Pair<String, Int>, newItem: Pair<String, Int>): Boolean =
            oldItem.second == newItem.second

        override fun areContentsTheSame(oldItem: Pair<String, Int>, newItem: Pair<String, Int>): Boolean =
            oldItem == newItem
    }
}
