package com.pinyin.quicklaunch

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 深浅模式在进程一启动就定好，避免首帧闪错色
        applyAppearance()

        // 预热拼音字典，避免首次搜索时卡顿
        Thread {
            runCatching { PinyinEngine.ensureLoaded(this) }
        }.start()
    }

    /** 把 prefs.appearance 翻译成 AppCompatDelegate 的夜间模式 */
    fun applyAppearance() {
        val mode = when (Prefs(this).appearance) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
