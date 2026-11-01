package com.aistra.hail.app

import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.ui.api.ApiActivity

object HailApi {
    /** @since 0.5.0 */
    const val ACTION_LAUNCH = "${BuildConfig.APPLICATION_ID}.action.LAUNCH"

    /**
     * Optional boolean extra [HailData.KEY_FORCE] (also usable as the `force` query parameter on
     * the `hail://freeze` schema) freezes the target app even if [HailData.excludeForegroundApp]
     * would otherwise protect it for being the current foreground app.
     * @since 0.5.0
     */
    const val ACTION_FREEZE = "${BuildConfig.APPLICATION_ID}.action.FREEZE"

    /** @since 0.5.0 */
    const val ACTION_UNFREEZE = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE"

    /** @since 1.1.0 */
    const val ACTION_FREEZE_TAG = "${BuildConfig.APPLICATION_ID}.action.FREEZE_TAG"

    /** @since 1.1.0 */
    const val ACTION_UNFREEZE_TAG = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE_TAG"

    /** @since 0.5.0 */
    const val ACTION_FREEZE_ALL = "${BuildConfig.APPLICATION_ID}.action.FREEZE_ALL"

    /** @since 0.5.0 */
    const val ACTION_UNFREEZE_ALL = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE_ALL"

    /** @since 1.0.0 */
    const val ACTION_FREEZE_NON_WHITELISTED =
        "${BuildConfig.APPLICATION_ID}.action.FREEZE_NON_WHITELISTED"

    /** @since 1.3.0 */
    const val ACTION_FREEZE_AUTO = "${BuildConfig.APPLICATION_ID}.action.FREEZE_AUTO"

    /** @since 0.6.0 */
    const val ACTION_LOCK = "${BuildConfig.APPLICATION_ID}.action.LOCK"

    /** @since 0.6.0 */
    const val ACTION_LOCK_FREEZE = "${BuildConfig.APPLICATION_ID}.action.LOCK_FREEZE"

    /** @since 1.6.0 */
    const val ACTION_ADD_WHITELIST = "${BuildConfig.APPLICATION_ID}.action.ADD_WHITELIST"

    /** @since 1.6.0 */
    const val ACTION_REMOVE_WHITELIST = "${BuildConfig.APPLICATION_ID}.action.REMOVE_WHITELIST"

    /**
     * Tells Hail that Samsung DeX (desktop mode) is currently active/inactive, so
     * [com.aistra.hail.utils.HSamsungDex] can use that instead of guessing via reflection —
     * Samsung's own DeX-state APIs are undocumented, hidden, and have shifted or been stubbed out
     * across One UI/Android versions, making them unreliable. Meant to be driven by an automation
     * app (e.g. MacroDroid) that has its own reliable way to detect DeX, fired via `am broadcast`
     * at [com.aistra.hail.receiver.ApiReceiver] on entering/exiting DeX. Set up both triggers
     * symmetrically — Hail has no way to tell a stale "still in DeX" flag from a current one, so a
     * missing exit trigger leaves it stuck maximizing every launch until [ACTION_DEX_MODE_OFF]
     * eventually fires.
     * @since 1.10.0
     */
    const val ACTION_DEX_MODE_ON = "${BuildConfig.APPLICATION_ID}.action.DEX_MODE_ON"

    /** @since 1.10.0 */
    const val ACTION_DEX_MODE_OFF = "${BuildConfig.APPLICATION_ID}.action.DEX_MODE_OFF"

    /**
     * Turns [HailData.excludeForegroundApp] on/off remotely, e.g. from an automation app, without
     * having to open Settings.
     * @since 1.10.0
     */
    const val ACTION_EXCLUDE_FOREGROUND_APP_ON =
        "${BuildConfig.APPLICATION_ID}.action.EXCLUDE_FOREGROUND_APP_ON"

    /** @since 1.10.0 */
    const val ACTION_EXCLUDE_FOREGROUND_APP_OFF =
        "${BuildConfig.APPLICATION_ID}.action.EXCLUDE_FOREGROUND_APP_OFF"

    /**
     * Turns [HailData.apiFreezeToast] (the "Freeze/unfreeze toast for automation" setting) on/off
     * remotely, e.g. from an automation app, without having to open Settings.
     * @since 1.10.0
     */
    const val ACTION_FREEZE_TOAST_ON = "${BuildConfig.APPLICATION_ID}.action.FREEZE_TOAST_ON"

    /** @since 1.10.0 */
    const val ACTION_FREEZE_TOAST_OFF = "${BuildConfig.APPLICATION_ID}.action.FREEZE_TOAST_OFF"

    fun getIntentForPackage(action: String, packageName: String) =
        Intent(action).setClass(app, ApiActivity::class.java).putExtra(HailData.KEY_PACKAGE, packageName)

    fun Intent.addTag(tag: String) = putExtra(HailData.KEY_TAG, tag)

    fun getIntentForTag(action: String, tag: String) =
        Intent(action).setClass(app, ApiActivity::class.java).addTag(tag)
}