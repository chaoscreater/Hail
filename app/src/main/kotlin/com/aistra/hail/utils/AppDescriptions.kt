package com.aistra.hail.utils

import android.content.pm.ApplicationInfo
import android.os.Build
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData

/**
 * Builds the prompt that asks an AI chat to describe the apps added to Hail, and parses the reply
 * back into per-app descriptions.
 */
object AppDescriptions {
    const val MAX_LENGTH = 300

    private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** Apps in Hail with no description yet; hidden apps are only included when [includeHidden]. */
    fun appsNeedingDescription(includeHidden: Boolean): List<AppInfo> = HailData.checkedList.filter {
        it.description.isBlank() && (includeHidden || it.packageName !in HailData.hiddenApps)
    }

    fun hiddenAppsNeedingDescription(): Int =
        HailData.checkedList.count { it.description.isBlank() && it.packageName in HailData.hiddenApps }

    fun buildPrompt(apps: List<AppInfo>): String = app.getString(
        R.string.description_ai_prompt,
        apps.joinToString("\n") { "${it.name.toString().replace('|', '/')} | ${it.packageName} | ${installSource(it)}" }
    )

    /**
     * Reads `package.name | description` lines from [text]. AI chats often echo the app list's
     * `name | package.name | ...` layout instead, so the package name may be the first or second
     * column, and the description is always the last column. Bullets, backticks and markdown table
     * pipes are tolerated. Lines the AI marked UNKNOWN are dropped. Only the first line for each
     * package is kept.
     */
    fun parse(text: String): List<Pair<String, String>> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim().trim('|').trim().removePrefix("- ").removePrefix("* ").removePrefix("• ")
        val columns = line.split('|').map { it.trim() }
        if (columns.size < 2) return@mapNotNull null
        val packageIndex = if (columns.size >= 3 && isPackageName(columns[1])) 1 else 0
        if (!isPackageName(columns[packageIndex])) return@mapNotNull null
        val packageName = columns[packageIndex].trim('`', '*')
        val description = columns.last().trim('`').replace(WHITESPACE_REGEX, " ").take(MAX_LENGTH)
        if (description.isEmpty() || description.trim('.', ' ').equals("UNKNOWN", ignoreCase = true)) {
            return@mapNotNull null
        }
        packageName to description
    }.distinctBy { it.first }.toList()

    private fun isPackageName(column: String) = PACKAGE_REGEX.matches(column.trim('`', '*'))

    /**
     * Parsed entries that belong to an app in Hail and would change its description: only apps
     * with no description yet, or also those with one when [replaceExisting].
     */
    fun importable(
        parsed: List<Pair<String, String>>, replaceExisting: Boolean = false
    ): List<Pair<AppInfo, String>> = parsed.mapNotNull { (pkg, desc) ->
        HailData.checkedList.find { it.packageName == pkg }
            ?.takeIf { (replaceExisting || it.description.isBlank()) && it.description != desc }
            ?.let { it to desc }
    }

    @Suppress("DEPRECATION")
    private fun installSource(info: AppInfo): String {
        val pm = app.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(info.packageName).installingPackageName
            else pm.getInstallerPackageName(info.packageName)
        }.getOrNull()
        return when (installer) {
            "com.android.vending" -> app.getString(R.string.description_source_play)
            "org.fdroid.fdroid" -> app.getString(R.string.description_source_fdroid)
            "com.sec.android.app.samsungapps" -> app.getString(R.string.description_source_galaxy)
            "com.google.android.packageinstaller", "com.android.packageinstaller" ->
                app.getString(R.string.description_source_apk)

            null, "" -> app.getString(
                if (info.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true)
                    R.string.description_source_system else R.string.description_source_unknown
            )

            else -> installer
        }
    }
}
