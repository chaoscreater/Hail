package com.aistra.hail.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ActivityWidgetAppPickBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.aistra.hail.utils.NameComparator
import com.google.android.material.textview.MaterialTextView

/**
 * Shown by the launcher when an [AppLaunchWidgetProvider] widget is placed (or reconfigured on
 * API 31+, via long-press "Edit"). Picks one app from [HailData.checkedList] and binds it to the
 * widget id before returning [Activity.RESULT_OK].
 */
class AppLaunchWidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val binding = ActivityWidgetAppPickBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val apps = HailData.checkedList.filter { it.applicationInfo != null }.sortedWith(NameComparator)
        binding.emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE

        val adapter = PickAppAdapter(apps) { info -> onAppPicked(info.packageName) }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.searchText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString() ?: "")
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun onAppPicked(packageName: String) {
        AppLaunchWidgetProvider.setPackage(this, appWidgetId, packageName)
        AppLaunchWidgetProvider.updateWidget(this, AppWidgetManager.getInstance(this), appWidgetId)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    private inner class PickAppAdapter(
        private val apps: List<AppInfo>,
        private val onClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<PickAppAdapter.VH>() {

        private var displayed: List<AppInfo> = apps

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: MaterialTextView = view.findViewById(R.id.app_name)
            val pkg: MaterialTextView = view.findViewById(R.id.app_desc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_widget_app_pick, parent, false))

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val info = displayed[position]
            holder.name.text = info.name
            holder.pkg.text = info.packageName
            info.applicationInfo?.let {
                AppIconCache.loadIconBitmapAsync(this@AppLaunchWidgetConfigActivity, it, myUserId, holder.icon)
            } ?: holder.icon.setImageDrawable(packageManager.defaultActivityIcon)
            holder.view.setOnClickListener { onClick(info) }
        }

        fun filter(query: String) {
            displayed = if (query.isBlank()) apps
            else apps.filter {
                it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
            notifyDataSetChanged()
        }
    }
}
