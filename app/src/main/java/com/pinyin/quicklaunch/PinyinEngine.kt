package com.pinyin.quicklaunch

import android.content.Context

/**
 * 汉字 -> 全部读音 的离线字典。
 *
 * 数据来自 assets/pinyin_dict.txt：20892 个汉字，其中 4938 个是多音字，
 * 每个字最多保留 4 个读音（按常用度排序，第一个最常用）。
 *
 * 关键点：这里不做"取一个正确读音"的判断，而是把所有读音都展开成候选。
 * 用户要的就是"尽量模糊"——只要任一读音能对上，就算命中。
 */
object PinyinEngine {

    private const val DICT_ASSET = "pinyin_dict.txt"

    /** 单个名称最多展开多少条读音组合，防止组合爆炸 */
    private const val MAX_VARIANTS = 48

    private val dict = HashMap<Char, Array<String>>(24000)

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            context.applicationContext.assets.open(DICT_ASSET).bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEach
                    val tab = line.indexOf('\t')
                    if (tab != 1 || line.length <= tab + 1) return@forEach
                    val ch = line[0]
                    val reads = line.substring(tab + 1).split(',')
                    if (reads.isNotEmpty() && reads[0].isNotEmpty()) {
                        dict[ch] = reads.toTypedArray()
                    }
                }
            }
            loaded = true
        }
    }

    /**
     * 把应用名展开成多条 (全拼, 首字母) 候选。
     *
     * 例：「微信」-> (weixin, wx)
     *     「重庆银行」-> cqyh / zqyh，全拼同理多套
     */
    fun buildVariants(text: String, limit: Int = MAX_VARIANTS): List<AppEntry.Variant> {
        if (text.isEmpty()) return emptyList()

        var fulls = ArrayList<String>(2).also { it.add("") }
        var inits = ArrayList<String>(2).also { it.add("") }
        // 与 fulls 平行：每条组合记录各字读音在 full 中的起始下标（命中标红用）
        var boundsList = ArrayList<IntArray>(2).also { it.add(IntArray(0)) }

        for (raw in text) {
            val ch = raw.lowercaseChar()
            val reads = dict[ch]

            if (reads == null) {
                // 非汉字：只接受 ASCII 字母数字（英文名、数字），其余（空格/标点/假名）直接跳过
                if (ch.code < 128 && ch.isLetterOrDigit()) {
                    val s = ch.toString()
                    val nf = ArrayList<String>(fulls.size)
                    val ni = ArrayList<String>(inits.size)
                    val nb = ArrayList<IntArray>(fulls.size)
                    for (i in fulls.indices) {
                        nf.add(fulls[i] + s)
                        ni.add(inits[i] + s)
                        nb.add(boundsList[i] + intArrayOf(fulls[i].length))
                    }
                    fulls = nf
                    inits = ni
                    boundsList = nb
                }
                continue
            }

            val nf = ArrayList<String>(minOf(fulls.size * reads.size, limit))
            val ni = ArrayList<String>(minOf(fulls.size * reads.size, limit))
            val nb = ArrayList<IntArray>(minOf(fulls.size * reads.size, limit))

            // 先遍历已有组合、再遍历读音，保证"全用第一读音"的组合排在最前，截断时优先保留
            loop@ for (i in fulls.indices) {
                val f = fulls[i]
                val ini = inits[i]
                for (r in reads) {
                    if (nf.size >= limit) break@loop
                    nf.add(f + r)
                    ni.add(ini + r[0])
                    nb.add(boundsList[i] + intArrayOf(f.length))
                }
            }

            if (nf.isEmpty()) break
            fulls = nf
            inits = ni
            boundsList = nb
        }

        val out = ArrayList<AppEntry.Variant>(fulls.size)
        val seen = HashSet<String>(fulls.size * 2)
        for (i in fulls.indices) {
            val f = fulls[i]
            if (f.isEmpty()) continue
            val ini = inits[i]
            if (seen.add("$f|$ini")) {
                out.add(AppEntry.Variant(f, ini, boundsList[i]))
            }
        }
        return out
    }

    /** T9 数字键 -> 字母 */
    fun t9Of(c: Char): Char = when (c) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        in 'w'..'z' -> '9'
        else -> c
    }
}
