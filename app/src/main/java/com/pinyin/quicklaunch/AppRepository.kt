package com.pinyin.quicklaunch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        PinyinEngine.ensureLoaded(context)

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (t: Throwable) {
            emptyList()
        }

        val prefs = Prefs(context)
        val seenPackages = HashSet<String>(resolved.size * 2)
        val result = ArrayList<AppEntry>(resolved.size)

        for (ri in resolved) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue

            // 同一个包只保留一个入口，避免同名应用重复出现
            if (!seenPackages.add(pkg)) continue
            if (pkg == context.packageName) continue

            val label = try {
                ri.loadLabel(pm).toString().trim()
            } catch (t: Throwable) {
                ""
            }
            if (label.isEmpty()) continue

            val icon = try {
                ri.loadIcon(pm)
            } catch (t: Throwable) {
                null
            }

            val variants = PinyinEngine.buildVariants(label)
            if (variants.isEmpty()) continue

            val entry = AppEntry(pkg, ai.name ?: "", label, icon, variants)
            entry.useCount = prefs.useCount(pkg)
            entry.lastUsed = prefs.lastUsed(pkg)
            result.add(entry)
        }

        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        result.sortWith(Comparator { a, b -> collator.compare(a.label, b.label) })
        result
    }

    fun launchIntentFor(pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)

    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    companion object {
        fun hasQueryAllPackages(context: Context): Boolean {
            val pm = context.packageManager
            return pm.checkPermission(
                android.Manifest.permission.QUERY_ALL_PACKAGES,
                context.packageName
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
