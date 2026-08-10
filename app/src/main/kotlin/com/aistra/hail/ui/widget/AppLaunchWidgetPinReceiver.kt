package com.aistra.hail.ui.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Target of the `successCallback` `PendingIntent` passed to `AppWidgetManager.requestPinAppWidget`
 * from `PinShortcutsDialogController.requestPinWidget`. Per that API's contract, a programmatic
 * pin request does *not* auto-launch [AppLaunchWidgetConfigActivity] the way manual placement
 * does — "the app could either show the configuration activity as a response to the callback, or
 * show it before calling the API" — so this receiver does the binding itself once the launcher
 * confirms placement and fills in [AppWidgetManager.EXTRA_APPWIDGET_ID].
 *
 * The target package rides in the request [Intent]'s `data` Uri rather than as an extra: only
 * action/data/component (not extras) distinguish one pending pin request from another, the same
 * reason [AppLaunchWidgetProvider]'s click `PendingIntent` keys itself off a per-widget Uri.
 */
class AppLaunchWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val packageName = intent.data?.lastPathSegment
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || packageName.isNullOrEmpty()) return
        AppLaunchWidgetProvider.setPackage(context, appWidgetId, packageName)
        AppLaunchWidgetProvider.updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
    }

    companion object {
        fun uriFor(packageName: String): Uri = Uri.parse("hailwidget://pin/$packageName")
    }
}
