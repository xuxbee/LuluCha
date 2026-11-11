package com.pinyin.quicklaunch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 应用列表磁盘缓存。
 *
 * 目的：打开软件时先读这里把列表「秒铺」出来，不必每次都等 PackageManager
 * 全量扫描（queryIntentActivities + 逐个 loadLabel/loadIcon + 拼音展开，
 * 200 个应用往往要几百毫秒）。真正的扫描放到退出界面时做，结果写回这里。
 *
 * 存储结构（全部在私有目录，不涉及任何存储权限）：
 * ```
 * files/apps_cache.json       {"v":1,"ts":..,"apps":[{"p":包名,"a":Activity,"l":名称,"u":包更新时间}]}
 * files/app_icons/<包名>.png  144px 图标，够 36dp @xxxhdpi 列表 1:1 显示
 * ```
 * `u` 用来判断包有没有升级过：升级过才重写图标，否则复用旧 PNG，避免每次退出
 * 都把 200 张图重新压缩一遍。
 */
class AppCache(private val context: Context) {

    private val listFile = File(context.filesDir, "apps_cache.json")
    private val iconDir = File(context.filesDir, "app_icons")

    private fun iconFile(pkg: String) = File(iconDir, pkg.replace('/', '_') + ".png")

    /** 读取缓存；没有缓存或解析失败返回 null（调用方退化为完整扫描） */
    fun read(): List<AppEntry>? {
        if (!listFile.exists()) return null
        return runCatching {
            PinyinEngine.ensureLoaded(context)
            val prefs = Prefs(context)
            val arr = JSONObject(listFile.readText()).optJSONArray("apps") ?: return null
            val out = ArrayList<AppEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("p")
                val act = o.optString("a")
                val label = o.optString("l")
                if (pkg.isEmpty() || label.isEmpty()) continue
                // 已卸载的应用直接丢掉，避免点一下报「无法启动」
                if (!isInstalled(pkg)) continue
                val variants = PinyinEngine.buildVariants(label)
                if (variants.isEmpty()) continue
                out.add(AppEntry(pkg, act, label, loadIcon(pkg), variants).also {
                    // 使用次数/最近使用单独存在 Prefs 里，这里补回来，
                    // 否则刚启动时「×N」标记和惯用排序会短暂丢失
                    it.useCount = prefs.useCount(pkg)
                    it.lastUsed = prefs.lastUsed(pkg)
                })
            }
            if (out.isEmpty()) null else out
        }.getOrNull()
    }

    /** 全量扫描的结果写回磁盘，供下次启动直接使用 */
    fun write(apps: List<AppEntry>) {
        runCatching {
            iconDir.mkdirs()
            val oldStamps = readStamps()
            val keep = HashSet<String>(apps.size * 2)
            val arr = JSONArray()

            for (a in apps) {
                val stamp = updatedAt(a.packageName)
                arr.put(
                    JSONObject()
                        .put("p", a.packageName)
                        .put("a", a.activityName)
                        .put("l", a.label)
                        .put("u", stamp)
                )

                val name = iconFile(a.packageName).name
                keep.add(name)
                val iconExists = iconFile(a.packageName).exists()
                if (!iconExists || oldStamps[a.packageName] != stamp) {
                    saveIcon(a.packageName, a.icon)
                }
            }

            listFile.writeText(
                JSONObject()
                    .put("v", 1)
                    .put("ts", System.currentTimeMillis())
                    .put("apps", arr)
                    .toString()
            )

            // 清掉已不在列表里的图标，避免缓存目录无限膨胀
            iconDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        }
    }

    fun clear() {
        runCatching {
            listFile.delete()
            iconDir.deleteRecursively()
        }
    }

    fun updatedAtFile(): Long = runCatching { listFile.lastModified() }.getOrDefault(0L)

    // ------------------------------------------------------------- internals

    private fun readStamps(): Map<String, Long> = runCatching {
        val arr = JSONObject(listFile.readText()).optJSONArray("apps") ?: return emptyMap()
        val map = HashMap<String, Long>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.optString("p")] = o.optLong("u", -1L)
        }
        map
    }.getOrDefault(emptyMap())

    private fun updatedAt(pkg: String): Long = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
    }.getOrDefault(-1L)

    private fun isInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun loadIcon(pkg: String): Drawable? {
        val f = iconFile(pkg)
        if (f.exists()) {
            runCatching {
                BitmapFactory.decodeFile(f.absolutePath)?.let {
                    return BitmapDrawable(context.resources, it)
                }
            }
        }
        // 图标文件缺失（被清过 / 首次升级路径）时退回实时读取
        return runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun saveIcon(pkg: String, drawable: Drawable?) {
        if (drawable == null) return
        runCatching {
            val size = ICON_PX
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val oldBounds = drawable.bounds
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            drawable.bounds = oldBounds
            iconFile(pkg).outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
            bmp.recycle()
        }
    }

    companion object {
        /** 36dp 列表图标在 xxxhdpi 下是 144px，按这个尺寸存一份就够 */
        private const val ICON_PX = 144
    }
}
