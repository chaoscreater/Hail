package com.aistra.hail.utils

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.aistra.hail.app.HailData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Maximizes app windows under Samsung DeX so they open full-screen instead of at DeX's small
 * default window size. Every target-app launch path (in-app tap via
 * [com.aistra.hail.ui.home.AppContextActions], a pinned/dynamic shortcut via
 * [com.aistra.hail.ui.api.ApiActivity], and the home-screen widget via
 * [com.aistra.hail.ui.widget.AppLaunchWidgetClickActivity]) funnels its launch `startActivity`
 * call through [startMaximized] so the behavior can't drift between entry points. Hail's own
 * window, launched directly by the user (not via a shortcut/widget), goes through the separate
 * [maximizeSelfIfNeeded] instead — see its doc for why the same [startMaximized] mechanism can't
 * reach that case.
 *
 * DeX/desktop-mode state is best read from an explicit external signal —
 * [com.aistra.hail.app.HailApi.ACTION_DEX_MODE_ON]/[com.aistra.hail.app.HailApi.ACTION_DEX_MODE_OFF],
 * typically driven by an automation app like MacroDroid — set via [HailData.dexModeExternal].
 * Without one, it falls back to probing Samsung's undocumented
 * `Configuration` accessors via reflection; the accessor name has shifted across One UI versions
 * (`semDesktopModeEnabled` historically, `hidden_semDesktopModeEnabled`/`isDexMode`/
 * `isNewDexMode`/`isDesktopModeEnabled` seen on newer builds — see [DEX_MODE_METHOD_CANDIDATES]),
 * and this reflection fallback is a safe no-op — plain [Context.startActivity] — on every
 * device/OS version where none of the known names exist.
 *
 * "DeX is active" alone isn't the right gate, though: when DeX is docked to an external monitor,
 * the phone's own screen and the DeX desktop are two separate Android displays, and a launch
 * should only maximize if it's actually landing on the DeX one. A new activity defaults to the
 * *same* display as whatever launched it, so [isOnExternalDisplay] checks which display [context]
 * — i.e. Hail itself — is currently showing on: internal (phone screen, even while DeX runs on
 * the dock) means launch normally; external (Hail is being driven from the DeX desktop) means
 * maximize. [buildMaximizedOptions] additionally pins the launch to that same display explicitly
 * via the public `setLaunchDisplayId`, rather than relying on default routing.
 *
 * Every decision logs to logcat (tag [TAG]); a DeX-positive result additionally logs to Hail's
 * in-app Logs screen via [ApiLog] (not every ordinary launch, to avoid a disk write on every
 * single app launch) — see [checkDexMode] and [startMaximized] when this isn't behaving as expected.
 */
object HSamsungDex {
    private const val TAG = "HSamsungDex"

    // android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM — hidden constant, inlined since
    // WindowConfiguration itself isn't part of the public SDK either.
    private const val WINDOWING_MODE_FREEFORM = 5

    /** [DexCheck.enabled] plus a human-readable [DexCheck.reason] explaining how that was determined. */
    private data class DexCheck(val enabled: Boolean, val reason: String)

    /**
     * No-arg boolean `Configuration` accessors that have been observed reporting Samsung DeX /
     * desktop-windowing state across OS versions, tried in this order until one both exists and
     * returns true. `semDesktopModeEnabled` was the historical name; newer One UI builds (seen on
     * a Samsung device running API 36) instead expose `hidden_semDesktopModeEnabled` (the same
     * flag, renamed) alongside `isDexMode`/`isNewDexMode`, and `isDesktopModeEnabled` is Android's
     * own upstream desktop-windowing flag that DeX now sits on top of on recent builds.
     */
    private val DEX_MODE_METHOD_CANDIDATES = listOf(
        "isDexMode", "isNewDexMode", "hidden_semDesktopModeEnabled", "semDesktopModeEnabled", "isDesktopModeEnabled"
    )

    /**
     * AOSP `Configuration` no-arg boolean accessors that are unrelated to DeX but would otherwise
     * clutter (or, if this list is ever misused for detection again, corrupt) the diagnostic
     * sweep below — e.g. `isNightModeActive` is Android's dark-mode flag.
     */
    private val KNOWN_UNRELATED_BOOLEAN_METHODS = listOf(
        "isNightModeActive", "isScreenRound", "isScreenWideColorGamut", "isScreenHdr"
    )

    /** True only when running on a Samsung device that's currently in DeX desktop mode. */
    fun isDexModeEnabled(context: Context): Boolean = checkDexMode(context).enabled

    /** Outcome of probing a single candidate accessor — kept distinct from a plain `Boolean?` so a
     *  found-but-threw case can be told apart from a plain "doesn't exist on this build". */
    private sealed class Probe {
        data class Found(val value: Boolean) : Probe()
        data class Error(val summary: String) : Probe()
        data object NotFound : Probe()
    }

    // Whether a given no-arg method exists on Configuration (and is reachable via plain
    // reflection) can't change during a process's lifetime, even though the *value* it returns
    // can — so the lookup is cached (once resolved or confirmed absent) and only the cheap
    // invoke() is repeated on every call. Only exercised when no external signal is configured;
    // most launches skip checkDexMode's reflection path entirely once one is.
    private val resolvedMethods = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()
    private val unresolvableMethods = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Every other no-arg boolean `Configuration` accessor, computed once and reused — see [checkDexMode]. */
    private val sweepCandidateNames: List<String> by lazy {
        runCatching {
            Configuration::class.java.declaredMethods
                .filter {
                    it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType &&
                        it.name !in DEX_MODE_METHOD_CANDIDATES && it.name !in KNOWN_UNRELATED_BOOLEAN_METHODS
                }
                .map { it.name }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun checkDexMode(context: Context): DexCheck {
        // An automation app (e.g. MacroDroid, via HailApi.ACTION_DEX_MODE_ON/OFF) explicitly
        // telling us the current state is authoritative — Samsung's own DeX-state APIs are
        // undocumented and have proven unreliable (renamed, stubbed, or outright false-positive
        // depending on OS build), so trust an explicit external signal over guessing via
        // reflection whenever one has ever been set.
        HailData.dexModeExternal?.let { external ->
            val reason = "external signal (HailApi.ACTION_DEX_MODE_ON/OFF) = $external"
            HLog.i(TAG, reason)
            return DexCheck(external, reason)
        }
        val config = context.resources.configuration
        val details = mutableListOf<String>()
        for (name in DEX_MODE_METHOD_CANDIDATES) {
            when (val probe = probeBooleanNoArg(config, Configuration::class.java, name)) {
                is Probe.Found -> {
                    details.add("$name()=${probe.value}")
                    if (probe.value) {
                        val reason = "Configuration.$name() = true"
                        HLog.i(TAG, reason)
                        return DexCheck(true, reason)
                    }
                }

                is Probe.Error -> details.add("$name()=ERROR[${probe.summary}]")
                Probe.NotFound -> {} // not present on this build — silently move to the next candidate
            }
        }
        // None of the named candidates panned out (one, hidden_semDesktopModeEnabled, turned out
        // to return a non-boolean sentinel rather than real data — likely stubbed by Android's
        // hidden-API hardening on very new OS builds). Dump every OTHER no-arg boolean method
        // Configuration declares purely for diagnostics — NOT as a detection signal. An earlier
        // version of this sweep treated any true result as "DeX detected", which false-positived
        // on unrelated flags like isNightModeActive() (Android's dark-mode state, coincidentally
        // true whenever dark mode is on, regardless of DeX). Never resurrect that: only the named
        // candidates above may ever set [DexCheck.enabled] true.
        val sweepResults = sweepCandidateNames.associateWith { name ->
            when (val probe = probeBooleanNoArg(config, Configuration::class.java, name)) {
                is Probe.Found -> probe.value.toString()
                is Probe.Error -> "ERR[${probe.summary}]"
                Probe.NotFound -> "NF"
            }
        }
        val reason = buildString {
            append("${Build.MANUFACTURER} ${Build.BRAND} API ${Build.VERSION.SDK_INT} — ")
            append(if (details.isEmpty()) "none of $DEX_MODE_METHOD_CANDIDATES found" else details.joinToString(", "))
            append("; other Configuration boolean methods (diagnostic only, never used for detection): ")
            append(if (sweepResults.isEmpty()) "none" else sweepResults.entries.joinToString(", ") { "${it.key}()=${it.value}" })
        }
        HLog.i(TAG, reason)
        return DexCheck(false, reason)
    }

    /**
     * Probes the no-arg boolean method [methodName] on [instance]: tries plain reflection first,
     * then falls back through [HiddenApiBypass] on P+ in case plain reflection is blocked. Reports
     * [Probe.Error] (with the concrete exception) rather than treating a found-but-failing method
     * the same as one that's simply absent — a hidden-API–hardened method can appear in a bulk
     * [Class.getDeclaredMethods] scan yet still throw when looked up/invoked by name, and that
     * distinction matters for diagnosing exactly where detection is breaking.
     */
    private fun probeBooleanNoArg(instance: Any, clazz: Class<*>, methodName: String): Probe {
        fun Result<Any?>.toProbe(prefix: String): Probe? = fold(
            onSuccess = { value ->
                when (value) {
                    is Boolean -> Probe.Found(value)
                    else -> Probe.Error("$prefix non-boolean return: $value")
                }
            },
            onFailure = { e -> if (e is NoSuchMethodException) null else Probe.Error("$prefix${e.javaClass.simpleName}: ${e.message}") }
        )

        if (methodName !in unresolvableMethods) {
            val method = resolvedMethods[methodName] ?: runCatching {
                clazz.getDeclaredMethod(methodName).apply { isAccessible = true }
            }.getOrNull()?.also { resolvedMethods[methodName] = it }

            if (method != null) {
                runCatching { method.invoke(instance) }.toProbe("")?.let { return it }
            } else {
                unresolvableMethods += methodName
            }
        }

        if (HTarget.P) {
            runCatching {
                HiddenApiBypass.invoke(clazz, instance, methodName)
            }.toProbe("bypass ")?.let { return it }
        }
        return Probe.NotFound
    }

    /**
     * Starts [intent] from [context]. When DeX mode is active, [HailData.maximizeAppsInDex] is
     * enabled, and [context] is currently showing on DeX's external display (not the phone's own
     * screen), the target app is launched maximized as a freeform window filling that display;
     * otherwise this is identical to a plain [Context.startActivity]. Building the maximized
     * launch options can never fail the launch itself — any error here just falls back to the
     * default DeX window size.
     */
    fun startMaximized(context: Context, intent: Intent) {
        val pkg = intent.component?.packageName ?: intent.`package` ?: "?"
        if (!HailData.maximizeAppsInDex) {
            context.startActivity(intent)
            return
        }
        val dexCheck = checkDexMode(context)
        val displayId = currentDisplayId(context)
        val onExternalDisplay = displayId != null && displayId != Display.DEFAULT_DISPLAY
        if (!dexCheck.enabled || !onExternalDisplay) {
            // The common case (not in DeX, or Hail itself is on the phone's own screen even
            // though DeX is running on the dock) — logcat only. Persisting a Logs-screen entry (a
            // disk read-modify-write of api_logs.json) for every single ordinary launch was only
            // ever meant for diagnosing detection itself; skip it now that detection works.
            val why = if (!dexCheck.enabled) "DeX mode not detected (${dexCheck.reason})"
            else "Hail is on the internal display (id=$displayId), not DeX's external one"
            HLog.i(TAG, "[$pkg] $why — launched normally")
            context.startActivity(intent)
            return
        }
        val built = buildMaximizedOptions(context, displayId)
        val detail = if (built != null) "DeX mode detected (${dexCheck.reason}) — launched maximized (${built.detail}${resizeCompatSuffix(pkg)})"
        else "DeX mode detected (${dexCheck.reason}), but building maximized launch options failed — launched normally"
        HLog.i(TAG, "[$pkg] $detail")
        ApiLog.logAsync("DeX Launch Check", pkg, detail)
        context.startActivity(intent, built?.bundle)
        if (built != null) scheduleForcedResize(pkg, built.bounds)
    }

    /**
     * Called once from [com.aistra.hail.ui.main.MainActivity.onCreate] to maximize Hail's own
     * window when Hail itself (not a shortcut/widget launch of a target app) is opened while
     * running on Samsung DeX's external display. [startMaximized] can't cover this case: it works
     * by attaching maximized [ActivityOptions] to the `startActivity` call that creates the
     * window, but the `startActivity` call that creates *Hail's own* window is made by the
     * launcher, not by Hail — there's no hook to attach anything to before that window exists.
     *
     * Instead this resizes the already-running task directly via [HShizuku.resizeTask]. That's
     * sufficient (no windowing-mode change needed, unlike [buildMaximizedOptions]) because Hail's
     * task is already a freeform window simply by virtue of running on the DeX desktop — only its
     * bounds need to change. Requires Shizuku to already be connected and granted permission
     * ([HShizuku.isReady]); silently leaves the window at its default size otherwise, the same as
     * [startMaximized] silently falling back to a plain launch when it can't build options.
     */
    fun maximizeSelfIfNeeded(activity: Activity) {
        if (!HailData.maximizeAppsInDex) return
        val dexCheck = checkDexMode(activity)
        val displayId = currentDisplayId(activity)
        val onExternalDisplay = displayId != null && displayId != Display.DEFAULT_DISPLAY
        if (!dexCheck.enabled || !onExternalDisplay) {
            val why = if (!dexCheck.enabled) "DeX mode not detected (${dexCheck.reason})"
            else "Hail is on the internal display (id=$displayId), not DeX's external one"
            HLog.i(TAG, "[self] $why — left at default size")
            return
        }
        val detail = if (!HShizuku.isReady)
            "DeX mode detected (${dexCheck.reason}), but Shizuku isn't connected/granted — left at default size"
        else usableScreenBounds(activity)?.let { bounds ->
            val resized = HShizuku.resizeTask(activity.taskId, bounds)
            "DeX mode detected (${dexCheck.reason}) — resizeTask(bounds=$bounds) ${if (resized) "succeeded" else "failed"}"
        } ?: "DeX mode detected (${dexCheck.reason}), but could not compute usable screen bounds — left at default size"
        HLog.i(TAG, "[self] $detail")
        ApiLog.logAsync("DeX Launch Check", activity.packageName, detail)
    }

    /**
     * The display [context] is currently shown on, or null if that can't be determined. Used to
     * tell DeX's external desktop display apart from the phone's own screen — see the class doc.
     */
    private fun currentDisplayId(context: Context): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.display?.displayId
        else @Suppress("DEPRECATION") context.getSystemService<WindowManager>()?.defaultDisplay?.displayId
    }.getOrNull()

    /**
     * Best-effort override of a non-resizable/capped-aspect-ratio manifest declaration via
     * Android's compat framework — see [HShizuku.forceResizable]. Kept as a cheap, harmless
     * addition to the launch for apps genuinely blocked that way, but it turned out NOT to be
     * what was needed for the case this was built for (nz.co.clubplus.app): that app was already
     * resizable-via-SDK-version, and its window was actually being re-sized by Samsung's own
     * orientation-based DeX window placement (see [scheduleForcedResize]), a completely separate
     * mechanism this override has no effect on.
     */
    private fun resizeCompatSuffix(pkg: String): String {
        if (!HShizuku.isReady) return ""
        return if (HShizuku.forceResizable(pkg)) ", compat-forced resizable" else ", compat resize override failed"
    }

    // Shared across all scheduleForcedResize() calls instead of a fresh CoroutineScope per launch —
    // a SupervisorJob so one failed/cancelled resize attempt can't affect later launches.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Some apps' windows get resized by the OS again right after a maximized launch, undoing the
     * bounds [buildMaximizedOptions] requested — observed with nz.co.clubplus.app, a
     * portrait-locked app: Samsung's own orientation-based DeX window placement (visible in
     * `dumpsys activity activities` as `mOrientationOverride=Enabled` on the task, sizing it to
     * the device's native portrait resolution and centering it) overrides the launch-time bounds
     * regardless of [HShizuku.forceResizable] or the app's own system "Aspect ratio" setting —
     * neither changed anything when tested by hand. What *does* work, confirmed by hand via `adb
     * shell am task resize`, is forcing a resize of the task after it already exists — the same
     * [HShizuku.resizeTask] mechanism [maximizeSelfIfNeeded] already uses for Hail's own window.
     * This retries briefly on a short delay because the task doesn't exist the instant
     * [Context.startActivity] returns, and resizing before the OS has finished applying its own
     * (wrong) initial bounds would just get overwritten by it moments later; it stops at the first
     * successful resize rather than fighting the OS indefinitely (a one-time correction, not a
     * standing override — reapplying forever could fight the user's own later manual resize too).
     */
    private fun scheduleForcedResize(pkg: String, bounds: Rect) {
        if (!HShizuku.isReady) return
        ioScope.launch {
            repeat(5) { attempt ->
                delay(300L * (attempt + 1))
                val taskId = HShizuku.findTaskId(pkg) ?: return@repeat
                val resized = HShizuku.resizeTask(taskId, bounds)
                val detail = "forced post-launch resizeTask(taskId=$taskId, bounds=$bounds) " +
                        "${if (resized) "succeeded" else "failed"} (attempt ${attempt + 1})"
                HLog.i(TAG, "[$pkg] $detail")
                if (resized) {
                    ApiLog.logAsync("DeX Launch Check", pkg, detail)
                    return@launch
                }
            }
        }
    }

    private data class MaximizedOptions(val bundle: Bundle, val detail: String, val bounds: Rect)

    private fun buildMaximizedOptions(context: Context, displayId: Int): MaximizedOptions? {
        if (!HTarget.N) {
            HLog.i(TAG, "Android < 7.0 (API ${Build.VERSION.SDK_INT}) — setLaunchBounds unavailable, skipping maximize")
            return null
        }
        return runCatching {
            val bounds = usableScreenBounds(context)
            if (bounds == null) {
                HLog.i(TAG, "Could not compute usable screen bounds — skipping maximize")
                return null
            }
            HLog.i(TAG, "Usable screen bounds: $bounds")
            val options = ActivityOptions.makeBasic()
            options.setLaunchBounds(bounds)
            // Pin the launch to the same (external, DeX) display Hail itself is on, rather than
            // relying on default same-display-as-caller routing — setLaunchDisplayId is public
            // SDK, not a hidden API.
            if (HTarget.O) options.setLaunchDisplayId(displayId)
            val windowingModeTier = setFreeformWindowingMode(options)
            if (windowingModeTier == null) {
                HLog.i(TAG, "Could not set freeform windowing mode via any fallback — launching with bounds only")
                return null
            }
            MaximizedOptions(options.toBundle(), "bounds=$bounds, display=$displayId, freeform via $windowingModeTier", bounds)
        }.onFailure {
            HLog.i(TAG, "buildMaximizedOptions failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /**
     * Full usable screen area: the largest bounds the target app's window could occupy on this
     * display, minus the status bar and the DeX taskbar. Deliberately [WindowManager]'s
     * *maximum* metrics, not its *current* ones — `currentWindowMetrics` reports the size of
     * Hail's own calling window, which under DeX is whatever (possibly small, non-maximized)
     * window Hail itself is currently running in, not the full desktop area we actually want the
     * launched app to fill.
     */
    private fun usableScreenBounds(context: Context): Rect? = runCatching {
        val windowManager = context.getSystemService<WindowManager>() ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()
            )
            Rect(
                metrics.bounds.left + insets.left,
                metrics.bounds.top + insets.top,
                metrics.bounds.right - insets.right,
                metrics.bounds.bottom - insets.bottom
            )
        } else {
            @Suppress("DEPRECATION")
            val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
            fun systemDimen(name: String) = context.resources.getIdentifier(name, "dimen", "android")
                .takeIf { it != 0 }?.let { context.resources.getDimensionPixelSize(it) } ?: 0
            val statusBarHeight = systemDimen("status_bar_height")
            val taskbarHeight = systemDimen("navigation_bar_height")
            Rect(0, statusBarHeight, size.x, size.y - taskbarHeight)
        }
    }.onFailure {
        HLog.i(TAG, "usableScreenBounds failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    private enum class WindowingModeStrategy { PUBLIC_METHOD, REFLECTION, FIELD_WRITE }

    // Which strategy actually works is a fixed fact about the OS build (can't change without an
    // OS update), so once one succeeds it's reused directly on every later call instead of
    // re-attempting whichever earlier tiers are already known to fail on every single DeX launch.
    @Volatile
    private var cachedWindowingModeStrategy: WindowingModeStrategy? = null

    /**
     * Sets freeform windowing mode on [options] via `ActivityOptions.setLaunchWindowingMode`,
     * a hidden API not part of the public SDK. Tries progressively more forceful fallbacks until
     * one works, since hidden-API enforcement/availability varies across Android versions and
     * OEM builds: a direct call in case it's actually exposed, then a reflective call routed
     * through [HiddenApiBypass] to get past the P+ hidden-API greylist, then finally writing the
     * backing field directly in case the setter itself is blocked. Returns which tier succeeded
     * (for diagnostics — a field-write "success" with no visible effect is a real failure mode of
     * its own, distinct from all three tiers cleanly failing), or null if all three failed.
     */
    private fun setFreeformWindowingMode(options: ActivityOptions): String? {
        val cached = cachedWindowingModeStrategy
        if (cached != null) {
            applyWindowingModeStrategy(cached, options)?.let { return it }
            // Stopped working — a single past success isn't a lifetime guarantee. Clear it and
            // fall through to a full re-probe below.
            cachedWindowingModeStrategy = null
        }
        for (strategy in WindowingModeStrategy.entries) {
            if (strategy == cached) continue // just tried and failed, above
            applyWindowingModeStrategy(strategy, options)?.let {
                cachedWindowingModeStrategy = strategy
                return it
            }
        }
        HLog.i(TAG, "All three approaches to set freeform windowing mode failed")
        return null
    }

    private fun applyWindowingModeStrategy(strategy: WindowingModeStrategy, options: ActivityOptions): String? =
        when (strategy) {
            WindowingModeStrategy.PUBLIC_METHOD -> runCatching {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(options, WINDOWING_MODE_FREEFORM)
                HLog.i(TAG, "Freeform windowing mode set via public method")
                "public method"
            }.onFailure {
                HLog.i(TAG, "Public setLaunchWindowingMode failed: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()

            WindowingModeStrategy.REFLECTION -> runCatching {
                if (HTarget.P) HiddenApiBypass.invoke(
                    ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM
                ) else ActivityOptions::class.java.getDeclaredMethod(
                    "setLaunchWindowingMode", Int::class.javaPrimitiveType
                ).apply { isAccessible = true }.invoke(options, WINDOWING_MODE_FREEFORM)
                val tier = if (HTarget.P) "HiddenApiBypass" else "getDeclaredMethod"
                HLog.i(TAG, "Freeform windowing mode set via reflection ($tier)")
                tier
            }.onFailure {
                HLog.i(TAG, "Reflective setLaunchWindowingMode failed: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()

            WindowingModeStrategy.FIELD_WRITE -> runCatching {
                ActivityOptions::class.java.getDeclaredField("mLaunchWindowingMode").apply {
                    isAccessible = true
                }.set(options, WINDOWING_MODE_FREEFORM)
                HLog.i(TAG, "Freeform windowing mode set via direct field write")
                "direct field write"
            }.onFailure {
                HLog.i(TAG, "Direct field write for mLaunchWindowingMode failed: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
        }
}
