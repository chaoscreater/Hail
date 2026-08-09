package com.aistra.hail.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.content.edit
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget backed by a single app added to Hail, picked via
 * [AppLaunchWidgetConfigActivity] when the widget is placed. Tapping it launches the app
 * directly through [AppManager.launchApp] (via [AppLaunchWidgetClickActivity]) — unlike pinned
 * shortcuts, it never shows the launch-or-freeze prompt, regardless of the shortcut prompt
 * setting.
 */
class AppLaunchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        prefs(context).edit {
            appWidgetIds.forEach { id -> remove(id.toString()) }
        }
    }

    companion object {
        private const val PREFS_NAME = "app_launch_widget"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getPackage(context: Context, appWidgetId: Int): String? =
            prefs(context).getString(appWidgetId.toString(), null)

        fun setPackage(context: Context, appWidgetId: Int, packageName: String) {
            prefs(context).edit { putString(appWidgetId.toString(), packageName) }
        }

        /**
         * Renders and pushes this widget's [RemoteViews]. Loads the app icon synchronously, so
         * call it off the main thread except right after a single user action (see
         * [AppLaunchWidgetConfigActivity]), where one icon decode is cheap enough to do inline.
         */
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val packageName = getPackage(context, appWidgetId) ?: return
            val applicationInfo = HPackages.getApplicationInfoOrNull(packageName)
            val label = applicationInfo?.loadLabel(context.packageManager)?.toString() ?: packageName

            val views = RemoteViews(context.packageName, R.layout.widget_app_launch)
            views.setContentDescription(R.id.widget_app_icon, label)
            views.setTextViewText(R.id.widget_app_label, label)
            if (applicationInfo != null) {
                val size = context.resources.getDimensionPixelSize(R.dimen.widget_icon_size)
                views.setImageViewBitmap(
                    R.id.widget_app_icon,
                    AppIconCache.getOrLoadBitmap(context, applicationInfo, HPackages.myUserId, size)
                )
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.mipmap.ic_launcher)
            }

            // Route the tap through a proxy Activity, not a broadcast straight to this receiver —
            // starting an Activity from inside a background-process receiver is blocked by
            // Android's background-activity-launch restrictions, while starting an Activity
            // directly from a widget tap is always allowed. See AppLaunchWidgetClickActivity.
            val clickIntent = Intent(context, AppLaunchWidgetClickActivity::class.java).apply {
                putExtra(AppLaunchWidgetClickActivity.EXTRA_PACKAGE, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Unique data Uri per widget id so FLAG_UPDATE_CURRENT doesn't collide two
                // widgets that happen to point at the same app.
                data = Uri.parse("hailwidget://launch/$appWidgetId")
            }
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
