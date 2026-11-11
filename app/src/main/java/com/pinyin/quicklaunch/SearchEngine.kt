package com.pinyin.quicklaunch

/**
 * 搜索打分。
 *
 * 命中质量从高到低：
 *   1. 全拼前缀        wei -> 微信          1000
 *   2. 首字母前缀      wx  -> 微信           930
 *   3. 全拼任意子串    ix  -> 微信(weixin)   820-  （起点越靠后扣分）
 *   4. 首字母子串      x   -> 微信(wx)       750-
 *   5. 全拼松散子序列  xn  -> 微信           430-  ← 「任意两个字母」主要靠这层
 *   6. 首字母松散子序列                      再降 80
 *
 * 纯数字查询（T9 面板）会先把拼音映射成 2-9 数字串再比较，
 * 所以按 "9 9 6" 也能命中 "wenxin/xinxi" 一类的名称。
 */
object SearchEngine {

    /** 命中结果：[matchStart, matchEnd) 是命中内容在 [matched] 全拼里的区间，-1 表示无（T9/无高亮） */
    class Hit(
        val app: AppEntry,
        val score: Long,
        val matched: String,
        val matchStart: Int = -1,
        val matchEnd: Int = -1
    )

    fun search(query: String, apps: List<AppEntry>, limit: Int = 400): List<Hit> {
        val q = query.trim().lowercase()

        if (q.isEmpty()) {
            val sorted = apps.sortedWith(
                compareByDescending<AppEntry> { it.useCount }
                    .thenByDescending { it.lastUsed }
                    .thenBy { it.label }
            )
            return sorted.take(limit).map { Hit(it, 0L, "") }
        }

        val numeric = q.all { it in '0'..'9' }
        val hits = ArrayList<Hit>()

        for (app in apps) {
            var best = 0
            var matched = ""
            var mStart = -1
            var mEnd = -1

            for (v in app.variants) {
                val m = if (numeric) t9Match(q, v) else matchVariant(q, v)
                if (m.score > best) {
                    best = m.score
                    matched = v.full
                    mStart = m.start
                    mEnd = m.end
                }
            }

            if (best > 0) {
                val bonus = minOf(app.useCount, 40) * 6L + recencyBonus(app.lastUsed)
                hits.add(Hit(app, best * 1000L + bonus, matched, mStart, mEnd))
            }
        }

        hits.sortWith(
            compareByDescending<Hit> { it.score }.thenBy { it.app.label }
        )
        return hits.take(limit)
    }

    private fun recencyBonus(last: Long): Long {
        if (last <= 0L) return 0L
        val hours = (System.currentTimeMillis() - last) / 3_600_000L
        return when {
            hours <= 6 -> 600L
            hours <= 24 -> 380L
            hours <= 72 -> 200L
            hours <= 168 -> 90L
            else -> 0L
        }
    }

    private class MatchResult(val score: Int, val start: Int, val end: Int)

    /**
     * 与原 scoreVariant 相同的优先级链打分，同时算出命中区间
     * （区间是 [matched] 全拼里的 [start, end)，用于标红；无区间时为 -1）
     */
    private fun matchVariant(q: String, v: AppEntry.Variant): MatchResult {
        if (v.full.startsWith(q)) return MatchResult(1000, 0, q.length)
        if (v.initials.startsWith(q)) {
            val (s, e) = iniSpan(v, 0, q.length)
            return MatchResult(930, s, e)
        }

        val fi = v.full.indexOf(q)
        if (fi > 0) return MatchResult(820 - minOf(fi * 5, 150), fi, fi + q.length)

        val ii = v.initials.indexOf(q)
        if (ii > 0) {
            val (s, e) = iniSpan(v, ii, ii + q.length)
            return MatchResult(750 - minOf(ii * 5, 150), s, e)
        }

        if (q.length >= 2) {
            val sp = subsequenceSpan(q, v.full)
            if (sp != null) {
                val gap = (sp[1] - sp[0]) - q.length
                val s = (430 - gap * 14 - v.full.length).coerceAtLeast(120)
                return MatchResult(s, sp[0], sp[1])
            }
            val s2 = subsequence(q, v.initials)
            if (s2 > 0) return MatchResult((s2 - 80).coerceAtLeast(60), -1, -1)
        }
        return MatchResult(0, -1, -1)
    }

    /** 把首字母区间 [a, b) 映射回全拼区间：起点取首个音节的起始，终点取末音节的结束 */
    private fun iniSpan(v: AppEntry.Variant, a: Int, b: Int): Pair<Int, Int> {
        if (v.bounds.size != v.initials.length || v.bounds.isEmpty()) return -1 to -1
        if (a < 0 || b > v.bounds.size) return -1 to -1
        val start = v.bounds[a]
        val end = if (b < v.bounds.size) v.bounds[b] else v.full.length
        return if (end > start) start to end else -1 to -1
    }

    /** 松散子序列命中时返回 [first, last+1) 区间，无匹配返回 null */
    private fun subsequenceSpan(q: String, s: String): IntArray? {
        if (s.length < q.length) return null
        var qi = 0
        var first = -1
        var last = -1
        for (i in s.indices) {
            if (qi < q.length && s[i] == q[qi]) {
                if (first < 0) first = i
                last = i
                qi++
            }
        }
        if (qi < q.length) return null
        return intArrayOf(first, last + 1)
    }

    /**
     * T9 匹配：把查询里的每个数字当作该键上 3~4 个字母的通配符，
     * 直接对拼音串做通配匹配（7 可匹配 p/q/r/s 任一），并返回命中区间用于标红。
     * 优先级链与字母查询一致。
     */
    private fun t9Match(q: String, v: AppEntry.Variant): MatchResult {
        // 整串完全相等：77 正好等于 QQ 的 T9，直接置顶
        if (v.initials.length == q.length && t9Find(q, v.initials) != null) {
            val (s, e) = iniSpan(v, 0, q.length)
            return MatchResult(1010, s, e)
        }
        if (v.full.length == q.length && t9Find(q, v.full) != null) {
            return MatchResult(1005, 0, v.full.length)
        }

        // 全拼前缀
        t9Find(q, v.full)?.let { if (it[0] == 0) return MatchResult(980, it[0], it[1]) }
        // 首字母前缀
        if (v.initials.length >= q.length && t9Find(q, v.initials)?.get(0) == 0) {
            val (s, e) = iniSpan(v, 0, q.length)
            return MatchResult(910, s, e)
        }

        // 全拼任意子串
        t9Find(q, v.full)?.let {
            if (it[0] > 0) return MatchResult(800 - minOf(it[0] * 5, 150), it[0], it[1])
        }
        // 首字母任意子串
        if (v.initials.length >= q.length) {
            val ii = t9Find(q, v.initials)
            if (ii != null && ii[0] > 0) {
                val (s, e) = iniSpan(v, ii[0], ii[1])
                return MatchResult(730 - minOf(ii[0] * 5, 150), s, e)
            }
        }

        if (q.length >= 2) {
            // 松散子序列：数字按顺序匹配键上字母即可，不要求连续
            t9SubsequenceSpan(q, v.full)?.let { sp ->
                val gap = (sp[1] - sp[0]) - q.length
                val s = (430 - gap * 14 - v.full.length).coerceAtLeast(120)
                return MatchResult(s, sp[0], sp[1])
            }
        }
        return MatchResult(0, -1, -1)
    }

    /** 通配查找：q 的每个数字匹配该键上任意字母，返回首个命中 [start, end)，无命中返回 null */
    private fun t9Find(q: String, s: String): IntArray? {
        if (s.length < q.length) return null
        val n = q.length
        outer@ for (start in 0..s.length - n) {
            for (j in 0 until n) {
                if (PinyinEngine.t9Of(s[start + j]) != q[j]) continue@outer
            }
            return intArrayOf(start, start + n)
        }
        return null
    }

    /** T9 松散子序列：数字按顺序命中键上字母即可，返回 [first, last+1) 区间，无匹配返回 null */
    private fun t9SubsequenceSpan(q: String, s: String): IntArray? {
        if (s.length < q.length) return null
        var qi = 0
        var first = -1
        var last = -1
        for (i in s.indices) {
            if (qi < q.length && PinyinEngine.t9Of(s[i]) == q[qi]) {
                if (first < 0) first = i
                last = i
                qi++
            }
        }
        if (qi < q.length) return null
        return intArrayOf(first, last + 1)
    }

    /** 松散子序列匹配：字母按顺序出现即可，不要求连续。跨度越小分越高 */
    private fun subsequence(q: String, s: String): Int {
        if (s.length < q.length) return 0
        var qi = 0
        var first = -1
        var last = -1
        for (i in s.indices) {
            if (qi < q.length && s[i] == q[qi]) {
                if (first < 0) first = i
                last = i
                qi++
            }
        }
        if (qi < q.length) return 0
        val gap = (last - first + 1) - q.length
        // 长度惩罚：同样命中时，名字拼音越短越精准（如 weixin 优先于 zhongguoyinxing）
        return (430 - gap * 14 - s.length).coerceAtLeast(120)
    }

    fun toT9(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(PinyinEngine.t9Of(c))
        return sb.toString()
    }

    /** 某个键盘键对应的应用：字母键 -> 该字母开头；数字键 -> 该 T9 数字开头 */
    fun appsForKey(key: String, apps: List<AppEntry>, limit: Int = 8): List<AppEntry> {
        if (key.isEmpty()) return emptyList()
        val k = key.lowercase()
        val numeric = k[0] in '0'..'9'

        return apps.asSequence()
            .filter { app ->
                app.variants.any { v ->
                    if (numeric) {
                        toT9(v.initials).startsWith(k) || toT9(v.full).startsWith(k)
                    } else {
                        v.initials.startsWith(k) || v.full.startsWith(k)
                    }
                }
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.useCount }
                    .thenByDescending { it.lastUsed }
                    .thenBy { it.label }
            )
            .take(limit)
            .toList()
    }
}
