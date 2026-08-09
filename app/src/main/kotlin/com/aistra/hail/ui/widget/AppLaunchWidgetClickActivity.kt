package com.aistra.hail.ui.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aistra.hail.app.AppManager

/**
 * Invisible proxy Activity that [AppLaunchWidgetProvider]'s click [android.app.PendingIntent]
 * targets, instead of launching the target app straight from a `BroadcastReceiver`.
 *
 * A widget tap only exempts the *immediate* PendingIntent target from Android's background-
 * activity-launch restrictions — a `startActivity` call made *from inside* a receiver that the
 * tap merely broadcast into is not exempt and gets silently dropped. Starting this Activity
 * directly is exempt (same as any launcher icon tap), and once it is actually starting in the
 * foreground, the `startActivity` it performs in [AppManager.launchApp] is unrestricted.
 */
class AppLaunchWidgetClickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_PACKAGE)?.let { AppManager.launchApp(this, it) }
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
