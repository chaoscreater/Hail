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
     * Reads `package.name | description` lines from [text], tolerating the bullets, backticks and
     * markdown table pipes AI chats like to add. Lines the AI marked UNKNOWN are dropped. Only the
     * first line for each package is kept.
     */
    fun parse(text: String): List<Pair<String, String>> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim().trim('|').trim().removePrefix("- ").removePrefix("* ").removePrefix("• ")
        val separator = line.indexOf('|')
        if (separator <= 0) return@mapNotNull null
        val packageName = line.substring(0, separator).trim().trim('`', '*')
        val description = line.substring(separator + 1).trim().trim('|').trim().trim('`')
            .replace(WHITESPACE_REGEX, " ").take(MAX_LENGTH)
        if (!PACKAGE_REGEX.matches(packageName)) return@mapNotNull null
        if (description.isEmpty() || description.trim('.', ' ').equals("UNKNOWN", ignoreCase = true)) {
            return@mapNotNull null
        }
        packageName to description
    }.distinctBy { it.first }.toList()

    /** Parsed entries that belong to an app in Hail that still has no description. */
    fun importable(parsed: List<Pair<String, String>>): List<Pair<AppInfo, String>> = parsed.mapNotNull { (pkg, desc) ->
        HailData.checkedList.find { it.packageName == pkg }?.takeIf { it.description.isBlank() }?.let { it to desc }
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
