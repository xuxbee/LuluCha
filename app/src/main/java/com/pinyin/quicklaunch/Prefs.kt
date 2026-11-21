package com.pinyin.quicklaunch

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("quicklaunch", Context.MODE_PRIVATE)

    fun useCount(pkg: String): Int = sp.getInt("use_$pkg", 0)

    fun lastUsed(pkg: String): Long = sp.getLong("last_$pkg", 0L)

    fun recordLaunch(pkg: String) {
        sp.edit()
            .putInt("use_$pkg", useCount(pkg) + 1)
            .putLong("last_$pkg", System.currentTimeMillis())
            .apply()
    }

    fun clearUsage() {
        sp.edit().clear().apply()
    }

    var keyboardMode: Int
        get() = sp.getInt("keyboard_mode", PinyinKeyboardView.MODE_T9)
        set(value) {
            sp.edit().putInt("keyboard_mode", value).apply()
        }

    var showPinyin: Boolean
        get() = sp.getBoolean("show_pinyin", true)
        set(value) {
            sp.edit().putBoolean("show_pinyin", value).apply()
        }

    /** 背景图不透明度，0-100（0 = 全透明） */
    var bgAlpha: Int
        get() = sp.getInt("bg_alpha", 55)
        set(value) {
            sp.edit().putInt("bg_alpha", value.coerceIn(0, 100)).apply()
        }

    /** 是否已看完首次开屏提醒 */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(value) {
            sp.edit().putBoolean("onboarded", value).apply()
        }

    /** 26 键字母显示大写 */
    var keyboardUppercase: Boolean
        get() = sp.getBoolean("keyboard_uppercase", false)
        set(value) {
            sp.edit().putBoolean("keyboard_uppercase", value).apply()
        }

    /** 键盘行高百分比：70~130（100 = 标准高度） */
    var keyHeight: Int
        get() = sp.getInt("key_height", 100)
        set(value) {
            sp.edit().putInt("key_height", value.coerceIn(70, 130)).apply()
        }

    /**
     * 键盘水平位置：
     * center（全屏）/ middle（单手居中）/ right（靠右，右手模式）/ left（靠左，左手模式）
     */
    var keyboardSide: String
        get() = sp.getString("keyboard_side", "center") ?: "center"
        set(value) {
            sp.edit().putString("keyboard_side", value).apply()
        }

    /** 面板（操作界面）底色，ARGB；默认近白 #FAFBFF */
    var panelColor: Int
        get() = sp.getInt("panel_color", 0xFFFAFBFF.toInt())
        set(value) {
            sp.edit().putInt("panel_color", value).apply()
        }

    /** 面板底色不透明度：20~100（100 = 完全不透明） */
    var panelAlpha: Int
        get() = sp.getInt("panel_alpha", 100)
        set(value) {
            sp.edit().putInt("panel_alpha", value.coerceIn(20, 100)).apply()
        }
}
