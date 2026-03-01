package com.aistra.hail.utils

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.aistra.hail.app.HailData
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object SettingsBackupManager {

    private const val KEY_VERSION = "version"
    private const val KEY_APPS = "apps"
    private const val KEY_TAGS = "tags"
    private const val KEY_HIDDEN_APPS = "hidden_apps"
    private const val KEY_PREFERENCES = "preferences"
    private const val BACKUP_VERSION = 2

    /**
     * Exports all settings (checked apps, tags, hidden apps, and shared preferences) to a JSON file at [uri].
     */
    fun exportToUri(context: Context, uri: Uri): Boolean = runCatching {
        val root = JSONObject()
        root.put(KEY_VERSION, BACKUP_VERSION)

        // --- Checked apps ---
        val appsArray = JSONArray()
        HailData.checkedList.forEach { appInfo ->
            val obj = JSONObject()
            obj.put(HailData.KEY_PACKAGE, appInfo.packageName)
            obj.put("pinned", appInfo.pinned)
            obj.put("whitelisted", appInfo.whitelisted)
            obj.put("tags", JSONArray(appInfo.tagIdList))
            appsArray.put(obj)
        }
        root.put(KEY_APPS, appsArray)

        // --- Tags (order preserved — list maintains insertion order) ---
        val tagsArray = JSONArray()
        HailData.tags.forEach { (name, id) ->
            val obj = JSONObject()
            obj.put(HailData.KEY_TAG, name)
            obj.put("id", id)
            tagsArray.put(obj)
        }
        root.put(KEY_TAGS, tagsArray)

        // --- Hidden apps ---
        val hiddenArray = JSONArray()
        HailData.hiddenApps.forEach { pkg -> hiddenArray.put(pkg) }
        root.put(KEY_HIDDEN_APPS, hiddenArray)

        // --- Shared preferences (all known keys) ---
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val prefsObj = JSONObject()
        val allPrefs = sp.all
        listOf(
            HailData.WORKING_MODE,
            HailData.BIOMETRIC_LOGIN,
            HailData.APP_THEME,
            HailData.ICON_PACK,
            HailData.GRAYSCALE_ICON,
            HailData.COMPACT_ICON,
            HailData.SYNTHESIZE_ADAPTIVE_ICONS,
            HailData.HOME_FONT_SIZE,
            HailData.FUZZY_SEARCH,
            HailData.NINE_KEY_SEARCH,
            HailData.TILE_ACTION,
            HailData.AUTO_FREEZE_AFTER_LOCK,
            HailData.AUTO_FREEZE_DELAY,
            HailData.SKIP_WHILE_CHARGING,
            HailData.SKIP_FOREGROUND_APP,
            HailData.SKIP_NOTIFYING_APP,
            HailData.SHOW_UNINSTALLED,
            HailData.DYNAMIC_SHORTCUT_ACTION,
            HailData.FILTER_USER_APPS,
            HailData.FILTER_SYSTEM_APPS,
            HailData.FILTER_FROZEN_APPS,
            HailData.FILTER_UNFROZEN_APPS,
            HailData.FILTER_ADDED_APPS,
            HailData.FILTER_UNADDED_APPS,
            HailData.FILTER_ADDED_USER_APPS,
            HailData.FILTER_UNADDED_USER_APPS,
            HailData.FILTER_ADDED_SYSTEM_APPS,
            HailData.FILTER_UNADDED_SYSTEM_APPS,
            "sort_by",
        ).forEach { key ->
            allPrefs[key]?.let { value ->
                when (value) {
                    is Boolean -> prefsObj.put(key, value)
                    is Float -> prefsObj.put(key, value)
                    is Int -> prefsObj.put(key, value)
                    is Long -> prefsObj.put(key, value)
                    is String -> prefsObj.put(key, value)
                    else -> {} // skip
                }
            }
        }
        root.put(KEY_PREFERENCES, prefsObj)

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write(root.toString(2))
            }
        }
        true
    }.getOrElse { it.printStackTrace(); false }

    /**
     * Imports settings from a JSON file at [uri].
     */
    fun importFromUri(context: Context, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        } ?: return false

        val root = JSONObject(text)

        // --- Restore tags (order is preserved from the JSON array) ---
        val tagsArray = root.optJSONArray(KEY_TAGS)
        if (tagsArray != null) {
            HailData.tags.clear()
            for (i in 0 until tagsArray.length()) {
                val obj = tagsArray.getJSONObject(i)
                val name = obj.getString(HailData.KEY_TAG)
                val id = obj.getInt("id")
                HailData.tags.add(name to id)
            }
            HailData.saveTags()
        }

        // --- Restore checked apps ---
        val appsArray = root.optJSONArray(KEY_APPS)
        if (appsArray != null) {
            HailData.checkedList.clear()
            for (i in 0 until appsArray.length()) {
                val obj = appsArray.getJSONObject(i)
                val packageName = obj.getString(HailData.KEY_PACKAGE)
                val pinned = obj.optBoolean("pinned", false)
                val whitelisted = obj.optBoolean("whitelisted", false)
                val tagsJsonArray = obj.optJSONArray("tags")
                val tagIdList: MutableList<Int> = if (tagsJsonArray != null) {
                    MutableList(tagsJsonArray.length()) { idx -> tagsJsonArray.getInt(idx) }
                } else {
                    mutableListOf(0)
                }
                HailData.checkedList.add(
                    com.aistra.hail.app.AppInfo(
                        packageName = packageName,
                        pinned = pinned,
                        whitelisted = whitelisted,
                        tagIdList = tagIdList
                    )
                )
            }
            HailData.saveApps()
        }

        // --- Restore hidden apps ---
        val hiddenArray = root.optJSONArray(KEY_HIDDEN_APPS)
        if (hiddenArray != null) {
            HailData.hiddenApps.clear()
            for (i in 0 until hiddenArray.length()) {
                HailData.hiddenApps.add(hiddenArray.getString(i))
            }
            HailData.saveHiddenApps()
        }

        // --- Restore shared preferences ---
        val prefsObj = root.optJSONObject(KEY_PREFERENCES)
        if (prefsObj != null) {
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            val editor = sp.edit()
            // These are stored as Float in SharedPreferences. JSON may serialize whole-number
            // floats (e.g. 14.0) as integers, causing a ClassCastException on getFloat() if
            // we naively write them with putInt().
            val floatKeys = setOf(HailData.HOME_FONT_SIZE, HailData.AUTO_FREEZE_DELAY)
            val keys = prefsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // working_mode is device-specific — skip it to avoid crashing
                // on devices that don't support the exported mode
                if (key == HailData.WORKING_MODE) continue
                when (val value = prefsObj.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is Int -> if (key in floatKeys) editor.putFloat(key, value.toFloat())
                              else editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    else -> {}
                }
            }
            editor.apply()
        }

        true
    }.getOrElse { it.printStackTrace(); false }
}
