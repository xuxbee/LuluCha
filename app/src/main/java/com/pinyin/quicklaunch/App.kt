package com.pinyin.quicklaunch

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 预热拼音字典，避免首次搜索时卡顿
        Thread {
            runCatching { PinyinEngine.ensureLoaded(this) }
        }.start()
    }
}
