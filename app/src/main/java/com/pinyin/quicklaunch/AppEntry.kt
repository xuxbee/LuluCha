package com.pinyin.quicklaunch

import android.graphics.drawable.Drawable

/**
 * 一个可启动的应用条目。
 *
 * [variants] 是名称的拼音展开：多音字的每一种读音组合都会生成一条候选，
 * 这样「重庆」会同时拥有 chongqing / zhongqing 两条全拼，
 * 「银行」会同时拥有 yinhang / yinxing 两条全拼。
 */
class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable?,
    val variants: List<Variant>
) {
    /**
     * @param bounds 每个字的读音在 [full] 里的起始下标（长度 = 字数）。
     *               用于把首字母命中映射回全拼区间，做命中标红。
     */
    class Variant(val full: String, val initials: String, val bounds: IntArray = IntArray(0))

    var useCount: Int = 0
    var lastUsed: Long = 0L

    val key: String get() = "$packageName/$activityName"
}
