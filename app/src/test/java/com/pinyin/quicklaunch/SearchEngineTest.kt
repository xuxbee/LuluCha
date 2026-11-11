package com.pinyin.quicklaunch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchEngineTest {

    private lateinit var apps: List<AppEntry>

    private fun app(name: String): AppEntry =
        AppEntry("pkg.$name", "$name.Activity", name, null, PinyinEngine.buildVariants(name))

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PinyinEngine.ensureLoaded(ctx)
        apps = listOf(
            "微信", "支付宝", "重庆银行", "中国银行", "招商银行",
            "哔哩哔哩", "小红书", "QQ音乐", "铁路12306"
        ).map { app(it) }
    }

    private fun top(query: String): String? =
        SearchEngine.search(query, apps).firstOrNull()?.app?.label

    @Test
    fun `多音字生成两套全拼`() {
        val variants = PinyinEngine.buildVariants("重庆银行").map { it.full }
        assertTrue("应包含 chong 读音: $variants", variants.contains("chongqingyinhang"))
        assertTrue("应包含 zhong 读音: $variants", variants.contains("zhongqingyinhang"))
    }

    @Test
    fun `首字母命中`() {
        assertEquals("微信", top("wx"))
        assertEquals("小红书", top("xhs"))
        assertEquals("支付宝", top("zfb"))
    }

    @Test
    fun `全拼前缀命中`() {
        assertEquals("微信", top("weixin"))
        assertEquals("哔哩哔哩", top("bili"))
    }

    @Test
    fun `任意两个字母模糊命中`() {
        // 微信 weixin 内部的连续子串
        assertEquals("微信", top("ix"))
        // 不连续，靠松散子序列
        assertEquals("微信", top("xn"))
    }

    @Test
    fun `多音字两种读音都能搜到`() {
        assertEquals("重庆银行", top("cq"))
        assertEquals("重庆银行", top("zq"))
    }

    @Test
    fun `英文数字名称可搜`() {
        assertEquals("铁路12306", top("12306"))
        assertEquals("QQ音乐", top("qq"))
    }

    @Test
    fun `T9数字模糊匹配`() {
        // 微信 -> weixin -> 934946，前缀 9 应命中
        val hits = SearchEngine.search("99", apps)
        assertTrue("T9 99 应命中微信: " + hits.map { it.app.label }, hits.any { it.app.label == "微信" })
    }

    @Test
    fun `T9命中拼音区间标红`() {
        // 77 = 键7上 p/q/r/s 的通配：QQ音乐 首字母 qq 整串相等，置顶且 qq 音节标红（0-2）
        val qq = SearchEngine.search("77", apps).first { it.app.label == "QQ音乐" }
        assertEquals(0, qq.matchStart)
        assertEquals(2, qq.matchEnd)

        // 99 恰好等于 wx 的 T9（w=9 x=9），首字母整串相等置顶，weixin 全段标红（0-6）
        val wx = SearchEngine.search("99", apps).first { it.app.label == "微信" }
        assertEquals(0, wx.matchStart)
        assertEquals(6, wx.matchEnd)

        // 97 = z/s 键9 + 键7(p/q/r/s) 通配，靠 chongqing 里的 q...y 松散命中重庆银行
        val hits = SearchEngine.search("97", apps)
        assertTrue("T9 97 应命中重庆银行: " + hits.map { it.app.label },
            hits.any { it.app.label == "重庆银行" })
    }

    @Test
    fun `匹配质量优先于使用次数`() {
        val wx = apps.first { it.label == "微信" }
        val alipay = apps.first { it.label == "支付宝" }
        alipay.useCount = 999
        wx.useCount = 0
        // qq 只应命中 QQ音乐，不应被支付宝的常用度抢走首位
        assertEquals("QQ音乐", top("qq"))
    }

    @Test
    fun `长按键对应的应用列表`() {
        val list = SearchEngine.appsForKey("w", apps)
        assertTrue("w 开头应包含微信: " + list.map { it.label }, list.any { it.label == "微信" })
    }

    @Test
    fun `命中拼音区间标红`() {
        // wx 是首字母命中：w=wei(0-3) x=xin(3-6)，整段音节标红
        val wx = SearchEngine.search("wx", apps).first { it.app.label == "微信" }
        assertEquals(0, wx.matchStart)
        assertEquals(6, wx.matchEnd)

        // ix 是全拼子串命中：weixin 里下标 2-4
        val ix = SearchEngine.search("ix", apps).first { it.app.label == "微信" }
        assertEquals(2, ix.matchStart)
        assertEquals(4, ix.matchEnd)

        // weix 是全拼前缀命中：0-4
        val weix = SearchEngine.search("weix", apps).first { it.app.label == "微信" }
        assertEquals(0, weix.matchStart)
        assertEquals(4, weix.matchEnd)

        // 多音字：cq 命中重庆银行，「重」读 chong 的那段音节标红（0-9）
        val cq = SearchEngine.search("cq", apps).first { it.app.label == "重庆银行" }
        assertTrue("变体应以 chongqing 开头: " + cq.matched, cq.matched.startsWith("chongqing"))
        assertEquals(0, cq.matchStart)
        assertEquals(9, cq.matchEnd)
    }
}
