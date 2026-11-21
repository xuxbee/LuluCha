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

    /**
     * 深浅模式：0 = 跟随系统（系统深色模式时面板变深、文字变白），
     * 1 = 浅色（浅背景黑字），2 = 深色（深背景白字）。
     */
    var appearance: Int
        get() = sp.getInt("appearance", 0)
        set(value) {
            sp.edit().putInt("appearance", value.coerceIn(0, 2)).apply()
        }

    /** 面板底色不透明度：20~100（100 = 完全不透明） */
    var panelAlpha: Int
        get() = sp.getInt("panel_alpha", 100)
        set(value) {
            sp.edit().putInt("panel_alpha", value.coerceIn(20, 100)).apply()
        }

    /**
     * 键盘按键不透明度：20~100（100 = 完全不透明）。
     * 只调按键本身的底色，面板底色由 [panelAlpha] 控制，互不影响。
     */
    var keyAlpha: Int
        get() = sp.getInt("key_alpha", 100)
        set(value) {
            sp.edit().putInt("key_alpha", value.coerceIn(20, 100)).apply()
        }

    /**
     * 单手模式下、操作面板外的背景：false = 完全透明（默认），true = 磨砂模糊。
     *
     * 备注一下两种效果的区别，供用户自己选择：
     * - 完全透明：面板外直接透出桌面，最干净；但因为窗口整体是透明的，
     *   部分机型每次启动时系统先展示的启动快照和真正的画面对不上，
     *   会「闪一下」。
     * - 磨砂模糊：面板外盖一层毛玻璃（需 Android 12+ 且系统允许跨窗口模糊），
     *   启动快照和实际画面差异小，基本不会闪；代价是面板外不是纯透明。
     */
    var narrowOutsideBlur: Boolean
        get() = sp.getBoolean("narrow_outside_blur", false)
        set(value) {
            sp.edit().putBoolean("narrow_outside_blur", value).apply()
        }
}
