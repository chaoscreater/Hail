package com.aistra.hail.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.view.WindowInsetsCompat
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.HailData

object HUI {
    /** Action broadcast (and notification channel id) used to signal that a launch/unfreeze
     *  failed because the backend (e.g. Shizuku) is unavailable. Detectable by automation
     *  apps such as MacroDroid to start Shizuku. */
    const val ACTION_SHIZUKU_REQUIRED = "com.aistra.hail.SHIZUKU_REQUIRED"
    private const val MACRODROID_PACKAGE = "com.arlosoft.macrodroid"

    /**
     * Fires every available signal so an external automation app can react to a failed
     * launch/unfreeze (typically: Shizuku not running). Safe to call from any context.
     *
     * 1. A package-targeted broadcast to MacroDroid — `setPackage` makes it explicit enough
     *    to bypass the Android 8+ implicit-broadcast ban on manifest receivers, and
     *    FLAG_INCLUDE_STOPPED_PACKAGES wakes MacroDroid even if it is in a stopped state.
     * 2. An untargeted broadcast for any other listener (Tasker, etc.).
     * 3. A notification fallback — MacroDroid's "Notification Received" trigger uses a
     *    NotificationListenerService and is unaffected by background broadcast limits.
     */
    fun notifyShizukuRequired(packageName: String) {
        runCatching {
            app.sendBroadcast(Intent(ACTION_SHIZUKU_REQUIRED).apply {
                setPackage(MACRODROID_PACKAGE)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("package", packageName)
            })
        }
        runCatching {
            app.sendBroadcast(Intent(ACTION_SHIZUKU_REQUIRED).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("package", packageName)
            })
        }
        if (!HailData.shizukuRequiredNotification) return
        runCatching {
            val channelId = "shizuku_required"
            val notifManager = NotificationManagerCompat.from(app)
            notifManager.createNotificationChannel(
                NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(app.getString(R.string.shizuku_required)).build()
            )
            notifManager.notify(
                201,
                NotificationCompat.Builder(app, channelId)
                    .setSmallIcon(R.drawable.ic_round_frozen)
                    .setContentTitle(app.getString(R.string.shizuku_required))
                    .setContentText(packageName)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
    /**
     * The types of edges that the UI will avoid by default,
     * including the status bar, navigation bar, and camera area.
     * */
    val INSETS_TYPE_DEFAULT = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    fun showToast(text: CharSequence, isLengthLong: Boolean = false) = Toast.makeText(
        app, text, if (isLengthLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    ).show()

    fun showToast(resId: Int, isLengthLong: Boolean = false) = showToast(app.getString(resId), isLengthLong)

    fun showToast(resId: Int, text: CharSequence, isLengthLong: Boolean = false) =
        showToast(app.getString(resId, text), isLengthLong)

    fun startActivity(action: String = Intent.ACTION_VIEW, uri: String): Boolean = runCatching {
        app.startActivity(
            Intent(action, Uri.parse(uri)).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun openLink(url: String): Boolean = startActivity(uri = url)

    fun copyText(text: String) = app.getSystemService<ClipboardManager>()
        ?.setPrimaryClip(ClipData.newPlainText(app.getString(R.string.app_name), text))

    fun pasteText(): String? = app.getSystemService<ClipboardManager>()?.primaryClip?.getItemAt(0)?.text?.toString()
}