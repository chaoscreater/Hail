package com.aistra.hail.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.*
import rikka.shizuku.Shizuku

class SettingsFragment : MainFragment(), MenuProvider {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val exportSettingsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        val ok = com.aistra.hail.utils.SettingsBackupManager.exportToUri(requireContext(), uri)
        if (ok) HUI.showToast(R.string.msg_settings_exported)
        else HUI.showToast(R.string.msg_settings_import_failed)
    }

    private val importSettingsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val ok = com.aistra.hail.utils.SettingsBackupManager.importFromUri(requireContext(), uri)
        if (ok) HUI.showToast(R.string.msg_settings_imported)
        else HUI.showToast(R.string.msg_settings_import_failed)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    ProvidePreferenceLocals {
                        SettingsScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val autoFreezeAfterLock = rememberPreferenceState(HailData.AUTO_FREEZE_AFTER_LOCK, false)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            listPreference(
                key = HailData.WORKING_MODE,
                defaultValue = HailData.MODE_DEFAULT,
                onValueChange = ::onWorkingModeChange,
                values = HailData.WORKING_MODE_VALUES,
                entriesId = R.array.working_mode_entries,
                titleId = R.string.working_mode,
                icon = Icons.Outlined.Adb,
                type = ListPreferenceType.ALERT_DIALOG
            )
            switchPreference(
                key = HailData.BIOMETRIC_LOGIN,
                defaultValue = false,
                titleId = R.string.action_biometric,
                icon = Icons.Outlined.Fingerprint
            )
            horizontalDivider()
            preferenceCategory(key = "customize", title = { Text(text = stringResource(R.string.title_customize)) })
            listPreference(
                key = HailData.APP_THEME,
                defaultValue = HailData.FOLLOW_SYSTEM,
                onValueChange = { _, value ->
                    app.setAppTheme(value)
                    true
                },
                values = HailData.APP_THEME_VALUES,
                entriesId = R.array.app_theme_entries,
                titleId = R.string.app_theme,
                icon = Icons.Outlined.DarkMode
            )
            listPreference(
                key = HailData.ICON_PACK,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, _ ->
                    AppIconCache.clear()
                    true
                },
                values = mutableListOf(HailData.ACTION_NONE).apply {
                    addAll(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME").let {
                        if (HTarget.T) app.packageManager.queryIntentActivities(
                            it, PackageManager.ResolveInfoFlags.of(0)
                        ) else app.packageManager.queryIntentActivities(it, 0)
                    }.map { it.activityInfo.packageName })
                },
                titleId = R.string.icon_pack,
                icon = Icons.Outlined.Palette,
                summary = { iconPackName(it) },
                valueToText = ::iconPackName
            )
            switchPreference(
                key = HailData.GRAYSCALE_ICON,
                defaultValue = true,
                titleId = R.string.grayscale_icon,
                icon = Icons.Outlined.FilterBAndW
            )
            switchPreference(
                key = HailData.COMPACT_ICON,
                defaultValue = false,
                titleId = R.string.compact_icon,
                icon = Icons.Outlined.Apps
            )
            switchPreference(
                key = HailData.SYNTHESIZE_ADAPTIVE_ICONS,
                defaultValue = false,
                titleId = R.string.synthesize_adaptive_icons,
                icon = Icons.Outlined.Layers
            )
            sliderPreference(
                key = HailData.HOME_FONT_SIZE,
                defaultValue = 14f,
                title = { Text(text = stringResource(R.string.home_font_size)) },
                valueRange = 11f..16f,
                valueSteps = 4,
                icon = { Icon(imageVector = Icons.Outlined.TextFields, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.FUZZY_SEARCH,
                defaultValue = false,
                titleId = R.string.fuzzy_search,
                icon = Icons.AutoMirrored.Outlined.ManageSearch
            )
            switchPreference(
                key = HailData.NINE_KEY_SEARCH,
                defaultValue = false,
                titleId = R.string.nine_key,
                icon = Icons.Outlined.Dialpad
            )
            listPreference(
                key = HailData.TILE_ACTION,
                defaultValue = HailData.tileAction,
                values = HailData.TILE_ACTION_VALUES,
                entriesId = R.array.tile_action_entries,
                titleId = R.string.tile_action,
                icon = Icons.Outlined.DashboardCustomize
            )
            horizontalDivider()
            preferenceCategory(key = "auto_freeze", title = { Text(text = stringResource(R.string.auto_freeze)) })
            switchPreference(
                rememberState = { autoFreezeAfterLock },
                onValueChange = { _, value ->
                    app.setAutoFreezeService(value)
                    true
                },
                titleId = R.string.auto_freeze_after_lock,
                icon = Icons.Outlined.ScreenLockPortrait
            )
            sliderPreference(
                key = HailData.AUTO_FREEZE_DELAY,
                defaultValue = 0f,
                title = { Text(text = stringResource(R.string.auto_freeze_delay)) },
                valueRange = 0f..30f,
                valueSteps = 29,
                enabled = { autoFreezeAfterLock.value },
                icon = { Icon(imageVector = Icons.Outlined.LockClock, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.SKIP_WHILE_CHARGING,
                defaultValue = false,
                titleId = R.string.skip_while_charging,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.BatteryChargingFull
            )
            switchPreference(
                key = HailData.SKIP_FOREGROUND_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    if (value && !HSystem.checkOpUsageStats(requireContext())) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_foreground_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.Android
            )
            switchPreference(
                key = HailData.SKIP_NOTIFYING_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    val isGranted = NotificationManagerCompat.getEnabledListenerPackages(requireContext())
                        .contains(requireContext().packageName)
                    if (value && !isGranted) {
                        app.setAutoFreezeServiceEnabled(true)
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_notifying_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.NotificationsActive
            )
            horizontalDivider()
            preferenceCategory(key = "backup_restore", title = { Text(text = stringResource(R.string.title_backup_restore)) })
            preference(
                key = "reorganize_tags",
                title = { Text(text = stringResource(R.string.action_reorganize_tags)) },
                icon = { Icon(imageVector = Icons.Outlined.Reorder, contentDescription = null) },
                onClick = { showReorganizeTagsDialog() }
            )
            preference(
                key = "hide_apps",
                title = { Text(text = stringResource(R.string.action_hide_apps)) },
                icon = { Icon(imageVector = Icons.Outlined.VisibilityOff, contentDescription = null) },
                onClick = { showHideAppsDialog() }
            )
            preference(
                key = "export_settings",
                title = { Text(text = stringResource(R.string.action_export_settings)) },
                icon = { Icon(imageVector = Icons.Outlined.Upload, contentDescription = null) },
                onClick = {
                    exportSettingsLauncher.launch("hail_settings_backup.json")
                }
            )
            preference(
                key = "import_settings",
                title = { Text(text = stringResource(R.string.action_import_settings)) },
                icon = { Icon(imageVector = Icons.Outlined.Download, contentDescription = null) },
                onClick = {
                    importSettingsLauncher.launch(arrayOf("application/json", "*/*"))
                }
            )
            horizontalDivider()
            preferenceCategory(key = "shortcuts", title = { Text(text = stringResource(R.string.title_shortcuts)) })
            preference(
                key = "add_pin_shortcut",
                title = { Text(text = stringResource(R.string.action_add_pin_shortcut)) },
                icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.Shortcut, contentDescription = null) },
                onClick = ::addPinShortcut
            )
            listPreference(
                key = HailData.DYNAMIC_SHORTCUT_ACTION,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, action ->
                    HShortcuts.removeAllDynamicShortcuts()
                    HShortcuts.addDynamicShortcutAction(action)
                    true
                },
                values = HailData.DYNAMIC_SHORTCUT_ACTIONS,
                entriesId = R.array.dynamic_shortcut_entries,
                titleId = R.string.dynamic_shortcut_action,
                icon = Icons.Outlined.AppShortcut
            )
            preference(
                key = "clear_dynamic_shortcuts",
                title = { Text(text = stringResource(R.string.action_clear_dynamic_shortcuts)) },
                icon = { Icon(imageVector = Icons.Outlined.CleaningServices, contentDescription = null) }) {
                HShortcuts.removeAllDynamicShortcuts()
                HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
            }
        }
    }

    private fun LazyListScope.horizontalDivider() = item { HorizontalDivider() }

    private fun LazyListScope.switchPreference(
        rememberState: @Composable () -> MutableState<Boolean>,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = item(key = titleId, contentType = "SwitchPreference") {
        val state = rememberState()
        SwitchPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            title = { Text(text = stringResource(titleId)) },
            enabled = enabled,
            icon = { Icon(imageVector = icon, contentDescription = null) })
    }

    private fun LazyListScope.switchPreference(
        key: String,
        defaultValue: Boolean,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = switchPreference(
        rememberState = { rememberPreferenceState(key, defaultValue) },
        onValueChange = onValueChange,
        titleId = titleId,
        enabled = enabled,
        icon = icon
    )

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @StringRes titleId: Int,
        icon: ImageVector,
        summary: @Composable (String) -> String,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU,
        valueToText: (String) -> String
    ) = item(key = key, contentType = "ListPreference") {
        val state = rememberPreferenceState(key, defaultValue)
        ListPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            values = values,
            title = { Text(text = stringResource(titleId)) },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            summary = { Text(text = summary(state.value)) },
            type = type,
            valueToText = { AnnotatedString(valueToText(it)) })
    }

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @ArrayRes entriesId: Int,
        @StringRes titleId: Int,
        icon: ImageVector,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU
    ) = listPreference(
        key = key,
        defaultValue = defaultValue,
        onValueChange = onValueChange,
        values = values,
        titleId = titleId,
        icon = icon,
        summary = { it.toEntry(values, entriesId) },
        type = type,
        valueToText = { it.toEntry(values, entriesId) })

    private fun String.toEntry(values: List<String>, @ArrayRes entriesId: Int): String =
        resources.getStringArray(entriesId)[values.indexOf(this)]

    private fun iconPackName(pack: String): String = if (pack == HailData.ACTION_NONE) getString(R.string.action_none)
    else HPackages.getApplicationInfoOrNull(pack)?.loadLabel(app.packageManager)?.toString() ?: pack

    private fun addPinShortcut() {
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_add_pin_shortcut)
            .setItems(R.array.pin_shortcut_entries) { _, which ->
                when (which) {
                    0 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_freeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_frozen_shortcut
                                )!!,
                                HailApi.ACTION_FREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_FREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    1 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_unfrozen_shortcut
                                )!!,
                                HailApi.ACTION_UNFREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_UNFREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    2 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_ALL,
                        getString(R.string.action_freeze_all),
                        Intent(HailApi.ACTION_FREEZE_ALL)
                    )

                    3 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_unfrozen_shortcut
                        )!!,
                        HailApi.ACTION_UNFREEZE_ALL,
                        getString(R.string.action_unfreeze_all),
                        Intent(HailApi.ACTION_UNFREEZE_ALL)
                    )

                    4 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_NON_WHITELISTED,
                        getString(R.string.action_freeze_non_whitelisted),
                        Intent(HailApi.ACTION_FREEZE_NON_WHITELISTED)
                    )

                    5 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!, HailApi.ACTION_LOCK, getString(R.string.action_lock), Intent(HailApi.ACTION_LOCK)
                    )

                    6 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!,
                        HailApi.ACTION_LOCK_FREEZE,
                        getString(R.string.action_lock_freeze),
                        Intent(HailApi.ACTION_LOCK_FREEZE)
                    )
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun onWorkingModeChange(rememberState: MutableState<String>, mode: String): Boolean {
        // Show/hide terminal menu.
        activity.invalidateOptionsMenu()
        when {
            mode.startsWith(HailData.OWNER) -> if (!HPolicy.isDeviceOwnerActive) {
                MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.title_set_owner)
                    .setMessage(getString(R.string.msg_set_owner, HPolicy.ADB_COMMAND))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(HPolicy.ADB_COMMAND) }.show()
                    .findViewById<MaterialTextView>(android.R.id.message)?.setTextIsSelectable(true)
                return false
            }

            mode.startsWith(HailData.DHIZUKU) -> return runCatching {
                Dhizuku.init(app)
                when {
                    Dhizuku.isPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                                    override fun onRequestPermission(grantResult: Int) {
                                        trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                    }
                                })
                                awaitClose()
                            }.first()
                            if (result) {
                                rememberState.value = mode
                                if (HTarget.O) HDhizuku.setDelegatedScopes()
                            }
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }

            mode.startsWith(HailData.SU) -> if (!HShell.checkSU) {
                HUI.showToast(R.string.permission_denied)
                return false
            }

            mode.startsWith(HailData.SHIZUKU) -> return runCatching {
                when {
                    Shizuku.isPreV11() -> throw IllegalStateException("unsupported shizuku version")
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
                    Shizuku.shouldShowRequestPermissionRationale() -> {
                        HUI.showToast(R.string.permission_denied)
                        false
                    }

                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                val shizukuRequestCode = 0
                                val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                                    if (requestCode != shizukuRequestCode) return@OnRequestPermissionResultListener
                                    trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                                Shizuku.addRequestPermissionResultListener(listener)
                                Shizuku.requestPermission(shizukuRequestCode)
                                awaitClose {
                                    Shizuku.removeRequestPermissionResultListener(listener)
                                }
                            }.first()
                            if (result) rememberState.value = mode
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.shizuku_missing)
                false
            }

            mode.startsWith(HailData.ISLAND) -> return runCatching {
                when {
                    mode == HailData.MODE_ISLAND_HIDE && HIsland.freezePermissionGranted() -> true
                    mode == HailData.MODE_ISLAND_SUSPEND && HIsland.suspendPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
                            requestPermissionLauncher.launch(
                                if (mode == HailData.MODE_ISLAND_HIDE) HIsland.PERMISSION_FREEZE_PACKAGE
                                else HIsland.PERMISSION_SUSPEND_PACKAGE
                            )
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }.also {
                if (it) {
                    HIsland.checkOwnerApp()
                }
            }

            mode.startsWith(HailData.PRIVAPP) -> if (!HPackages.isPrivilegedApp(app.packageName)) {
                HUI.showToast(R.string.permission_denied)
                return false
            }
        }

        return true
    }

    private suspend fun onTerminalResult(exitValue: Int, msg: String?) = withContext(Dispatchers.Main) {
        if (exitValue == 0 && msg.isNullOrBlank()) return@withContext
        MaterialAlertDialogBuilder(requireActivity()).apply {
            if (!msg.isNullOrBlank()) {
                if (exitValue != 0) {
                    setTitle(getString(R.string.operation_failed, exitValue.toString()))
                }
                setMessage(msg)
                setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(msg) }
            } else if (exitValue != 0) {
                setMessage(getString(R.string.operation_failed, exitValue.toString()))
            }
        }.setPositiveButton(android.R.string.ok, null).show().findViewById<MaterialTextView>(android.R.id.message)
            ?.setTextIsSelectable(true)
    }

    private fun showHideAppsDialog() {
        // Load all installed apps sorted by name
        val pm = requireContext().packageManager
        val allApps = com.aistra.hail.utils.HPackages.getInstalledApplications()
            .sortedWith(com.aistra.hail.utils.NameComparator)
        val hiddenSet = HailData.hiddenApps.toMutableSet()
        // Working hidden state array (indexed into allApps) — this is what the checkboxes in the list toggle
        val isHidden = allApps.map { it.packageName in hiddenSet }.toBooleanArray()

        val dialogView = layoutInflater.inflate(R.layout.dialog_tag_manage, null)
        val tagNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.input_layout)
        val tagNameEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_text)
        val searchEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_text)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.app_list)
        val filterRow = dialogView.findViewById<android.widget.LinearLayout>(R.id.filter_row)
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radio_group_type)
        val radioUser = dialogView.findViewById<android.widget.RadioButton>(R.id.radio_user)
        val radioSystem = dialogView.findViewById<android.widget.RadioButton>(R.id.radio_system)
        val checkHidden = dialogView.findViewById<android.widget.CheckBox>(R.id.check_hidden)
        val checkUnhidden = dialogView.findViewById<android.widget.CheckBox>(R.id.check_unhidden)
        val checkExcludeAdded = dialogView.findViewById<android.widget.CheckBox>(R.id.check_exclude_added)

        // Hide the tag-name field — we only need the filter row + search + list
        tagNameInput.visibility = android.view.View.GONE
        tagNameEdit.visibility = android.view.View.GONE
        filterRow.visibility = android.view.View.VISIBLE
        radioUser.isChecked = true

        val adapter = HideAppAdapter(allApps, isHidden, pm, recyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        fun applyFilter() {
            val showSystem = radioSystem.isChecked
            val showHiddenOnly = checkHidden.isChecked
            val showUnhiddenOnly = checkUnhidden.isChecked
            val excludeAddedToHome = checkExcludeAdded.isChecked
            val query = searchEdit.text?.toString() ?: ""
            adapter.filter(
                query,
                showSystem = showSystem,
                showHiddenOnly = showHiddenOnly,
                showUnhiddenOnly = showUnhiddenOnly,
                excludeAddedToHome = excludeAddedToHome
            )
        }

        // Hidden and Un-hidden are mutually exclusive: ticking one unticks the other
        checkHidden.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && checkUnhidden.isChecked) checkUnhidden.isChecked = false
            // Exclude sub-option only relevant under Un-hidden
            if (isChecked) {
                checkExcludeAdded.isChecked = false
                checkExcludeAdded.isEnabled = false
            }
            applyFilter()
        }
        checkUnhidden.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && checkHidden.isChecked) checkHidden.isChecked = false
            checkExcludeAdded.isEnabled = isChecked
            if (!isChecked) checkExcludeAdded.isChecked = false
            applyFilter()
        }
        checkExcludeAdded.setOnCheckedChangeListener { _, _ -> applyFilter() }

        radioGroup.setOnCheckedChangeListener { _, _ -> applyFilter() }

        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { applyFilter() }
        })

        // Apply initial filter: User apps, no hidden/unhidden filter
        applyFilter()

        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.action_hide_apps)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                HailData.hiddenApps.clear()
                allApps.forEachIndexed { i, info ->
                    if (isHidden[i]) HailData.hiddenApps.add(info.packageName)
                }
                HailData.saveHiddenApps()
                HUI.showToast(R.string.msg_hidden_apps_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { dialog ->
                // Once the dialog is laid out, compute the maximum height the RecyclerView
                // can occupy so the button bar is never obscured, then enforce it on every
                // subsequent filter update via the adapter callback.
                recyclerView.viewTreeObserver.addOnGlobalLayoutListener(object :
                    android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        val windowHeight = dialog.window?.decorView?.height?.takeIf { it > 0 } ?: return
                        // Reserve 75% of the window for the entire dialog content area
                        val maxContent = (windowHeight * 0.75).toInt()
                        val chrome = dialogView.height - recyclerView.height // title + filters + search
                        val maxList = (maxContent - chrome).coerceAtLeast(120)
                        adapter.maxHeight = maxList
                        if (recyclerView.height > maxList) {
                            recyclerView.layoutParams.height = maxList
                            recyclerView.requestLayout()
                        }
                    }
                })
            }
    }

    private inner class HideAppAdapter(
        private val source: List<android.content.pm.ApplicationInfo>,
        private val hidden: BooleanArray,
        private val pm: android.content.pm.PackageManager,
        private val recyclerView: androidx.recyclerview.widget.RecyclerView
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<HideAppAdapter.VH>() {

        /** Set by the dialog after first layout; enforced after every filter call. */
        var maxHeight: Int = Int.MAX_VALUE

        private var displayed: List<Pair<Int, android.content.pm.ApplicationInfo>> =
            source.mapIndexed { i, a -> i to a }

        inner class VH(val view: android.view.View) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val icon = view.findViewById<android.widget.ImageView>(R.id.app_icon)
            val name = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.app_name)
            val pkg = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.app_desc)
            val check = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.app_star)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_apps, parent, false))

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (srcIdx, info) = displayed[position]
            holder.name.text = info.loadLabel(pm)
            holder.pkg.text = info.packageName
            info.let {
                com.aistra.hail.utils.AppIconCache.loadIconBitmapAsync(
                    requireContext(), it, com.aistra.hail.utils.HPackages.myUserId, holder.icon
                )
            }
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = hidden[srcIdx]
            holder.view.setOnClickListener {
                hidden[srcIdx] = !hidden[srcIdx]
                holder.check.isChecked = hidden[srcIdx]
            }
            holder.check.setOnCheckedChangeListener { _, checked -> hidden[srcIdx] = checked }
        }

        fun filter(
            query: String,
            showSystem: Boolean = false,
            showHiddenOnly: Boolean = false,
            showUnhiddenOnly: Boolean = false,
            excludeAddedToHome: Boolean = false
        ) {
            displayed = source.mapIndexed { i, a -> i to a }.filter { (srcIdx, info) ->
                // User/System filter
                val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (showSystem != isSystem) return@filter false

                // Hidden / Un-hidden filter — based on the isHidden[] working array in this dialog session:
                // showHiddenOnly=true  → only apps the user has ticked as hidden in this dialog
                // showUnhiddenOnly=true → only apps NOT ticked as hidden
                // neither ticked       → show all
                val isHiddenNow = hidden[srcIdx]
                when {
                    showHiddenOnly && !isHiddenNow -> return@filter false
                    showUnhiddenOnly && isHiddenNow -> return@filter false
                }

                // "Exclude Apps already added to Home" sub-option (only applies under Un-hidden)
                if (showUnhiddenOnly && excludeAddedToHome && HailData.isChecked(info.packageName)) {
                    return@filter false
                }

                // Search filter
                if (query.isBlank()) return@filter true
                com.aistra.hail.utils.FuzzySearch.search(info.packageName, query) ||
                com.aistra.hail.utils.FuzzySearch.search(info.loadLabel(pm).toString(), query) ||
                (HailData.nineKeySearch && com.aistra.hail.utils.NineKeySearch.search(
                    query, info.packageName, info.loadLabel(pm).toString()
                )) ||
                com.aistra.hail.utils.PinyinSearch.searchPinyinAll(info.loadLabel(pm).toString(), query)
            }
            notifyDataSetChanged()
            // Re-enforce the height cap: shrink when list is short, clamp when it's tall
            recyclerView.post {
                val target = minOf(recyclerView.computeVerticalScrollRange(), maxHeight)
                    .coerceAtLeast(0)
                if (recyclerView.layoutParams.height != target) {
                    recyclerView.layoutParams.height = target
                    recyclerView.requestLayout()
                }
            }
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_terminal -> showTerminalDialog()
            R.id.action_remove_owner -> (requireActivity() as MainActivity).ownerRemoveDialog()
            R.id.action_help -> HUI.openLink(HailData.URL_README)
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_settings, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        if (HailData.workingMode.startsWith(HailData.SU) || HailData.workingMode.startsWith(
                HailData.SHIZUKU
            )
        ) menu.findItem(R.id.action_terminal).isVisible = true
        else if (HPolicy.isDeviceOwnerActive) menu.findItem(R.id.action_remove_owner).isVisible = true
    }

    private fun showTerminalDialog() {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.command)
        binding.editText.run {
            setSingleLine()
            filters = arrayOf()
        }
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_terminal).setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val result = AppManager.execute(binding.editText.text.toString())
                    onTerminalResult(result.first, result.second)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showReorganizeTagsDialog() {
        if (HailData.tags.size <= 1) {
            HUI.showToast(R.string.msg_no_tags_to_reorder)
            return
        }
        val workingTags = mutableStateListOf(*HailData.tags.toTypedArray())

        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AppTheme {
                    ReorderTagsList(workingTags)
                }
            }
        }

        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.action_reorganize_tags)
            .setView(composeView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                HailData.tags.clear()
                HailData.tags.addAll(workingTags)
                HailData.saveTags()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Composable
    private fun ReorderTagsList(tags: SnapshotStateList<Pair<String, Int>>) {
        val listState = rememberLazyListState()
        var draggedIndex by remember { mutableIntStateOf(-1) }
        var dragOffsetY by remember { mutableFloatStateOf(0f) }
        var itemHeightPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current

        Column {
            Text(
                text = stringResource(R.string.msg_reorganize_tags_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                itemsIndexed(tags) { index, tag ->
                    val isDragging = draggedIndex == index
                    Surface(
                        tonalElevation = if (isDragging) 8.dp else 0.dp,
                        shadowElevation = if (isDragging) 8.dp else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = if (isDragging) with(density) { dragOffsetY.toDp() } else 0.dp)
                            .onGloballyPositioned { coords ->
                                if (itemHeightPx == 0f) itemHeightPx = coords.size.height.toFloat()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { _, dragAmount ->
                                            dragOffsetY += dragAmount.y
                                            if (itemHeightPx > 0f) {
                                                val steps = (dragOffsetY / itemHeightPx).toInt()
                                                val target = (draggedIndex + steps).coerceIn(0, tags.size - 1)
                                                if (target != draggedIndex) {
                                                    tags.add(target, tags.removeAt(draggedIndex))
                                                    draggedIndex = target
                                                    dragOffsetY -= steps * itemHeightPx
                                                }
                                            }
                                        },
                                        onDragEnd = { draggedIndex = -1; dragOffsetY = 0f },
                                        onDragCancel = { draggedIndex = -1; dragOffsetY = 0f }
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DragHandle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Text(
                                text = tag.first,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}