package com.pinyin.quicklaunch

import android.os.Build
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun `主界面可正常创建并构建键盘`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)

        val keyboard = activity.findViewById<PinyinKeyboardView>(R.id.keyboard)
        assertNotNull("键盘必须存在", keyboard)

        if (keyboard.childCount == 0) keyboard.build()
        assertTrue("T9 面板应构建出行", keyboard.childCount >= 4)

        val rv = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvApps)
        assertNotNull("结果列表必须存在", rv)
        assertNotNull(rv.adapter)
    }

    @Test
    fun `切换到26键面板`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val keyboard = activity.findViewById<PinyinKeyboardView>(R.id.keyboard)

        keyboard.mode = PinyinKeyboardView.MODE_QWERTY
        assertTrue("26 键面板至少应有 4 行（数字行+字母行）", keyboard.childCount >= 4)

        val row1 = keyboard.getChildAt(0) as android.widget.LinearLayout
        assertTrue("第一行（数字行）应有 10 个键", row1.childCount == 10)
    }

    @Test
    fun `输入字母后查询状态更新`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val tvQuery = activity.findViewById<android.widget.TextView>(R.id.tvQuery)

        activity.onChar('w')
        activity.onChar('x')

        assertTrue("输入框应显示 wx", tvQuery.text.toString().startsWith("wx"))

        activity.onBackspace()
        assertTrue("退格后应只剩 w", tvQuery.text.toString().startsWith("w"))

        activity.onClear()
        assertTrue("清空后应显示提示", tvQuery.text.toString().isEmpty() ||
            tvQuery.text.toString() == activity.getString(R.string.search_hint))
    }

    @Test
    fun `T9底行有清空和提交键`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val keyboard = activity.findViewById<PinyinKeyboardView>(R.id.keyboard)
        if (keyboard.childCount == 0) keyboard.build()

        val texts = ArrayList<String>()
        var clearKey: View? = null
        for (i in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(i) as android.widget.LinearLayout
            for (j in 0 until row.childCount) {
                val f = row.getChildAt(j) as android.widget.FrameLayout
                val tv = f.getChildAt(0) as? android.widget.TextView ?: continue
                texts.add(tv.text.toString())
                if (tv.text == "清空") clearKey = f
            }
        }
        assertTrue("T9 应含清空键: $texts", texts.contains("清空"))
        assertTrue("T9 应含提交键: $texts",
            texts.contains(activity.getString(R.string.submit)))
        assertNotNull("清空键应可点击", clearKey)

        // 点清空能清掉已输入内容
        activity.onChar('w')
        clearKey!!.performClick()
        val tvQuery = activity.findViewById<android.widget.TextView>(R.id.tvQuery)
        assertTrue("点清空后应回到提示语",
            tvQuery.text.toString() == activity.getString(R.string.search_hint))
    }

    @Test
    fun `长按退格连续删除`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val keyboard = activity.findViewById<PinyinKeyboardView>(R.id.keyboard)
        if (keyboard.childCount == 0) keyboard.build()
        val tvQuery = activity.findViewById<android.widget.TextView>(R.id.tvQuery)

        activity.onChar('a')
        activity.onChar('b')
        activity.onChar('c')

        // T9 最底行：清空 / 0 / 退格
        val row = keyboard.getChildAt(4) as android.widget.LinearLayout
        val back = row.getChildAt(2)
        assertTrue("长按退格应触发连删", back.performLongClick())
        assertTrue("长按先删一个: " + tvQuery.text, tvQuery.text.toString().startsWith("ab"))

        // 逐个执行排队的连删任务，验证按住不放会继续删除
        val shadowLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        shadowLooper.runToNextTask()
        assertTrue("按住不放应继续删除: " + tvQuery.text,
            tvQuery.text.toString().startsWith("a"))
    }

    @Test
    fun `T9布局_退格在上清空0提交在最底行`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val keyboard = activity.findViewById<PinyinKeyboardView>(R.id.keyboard)
        if (keyboard.childCount == 0) keyboard.build()

        assertEquals("T9 应为 5 行", 5, keyboard.childCount)

        // 键内可能是单 TextView 或 竖排 LinearLayout（主字 + 字母小字）
        fun labelOf(key: View): String {
            val f = key as android.widget.FrameLayout
            return when (val child = f.getChildAt(0)) {
                is android.widget.TextView -> child.text.toString()
                is android.widget.LinearLayout ->
                    (child.getChildAt(0) as android.widget.TextView).text.toString()
                else -> ""
            }
        }

        // 第 1 行：26键（文本）+ 提交（文本，蓝色玻璃键）
        val top = keyboard.getChildAt(0) as android.widget.LinearLayout
        assertEquals(2, top.childCount)
        assertEquals("26键", labelOf(top.getChildAt(0)))
        assertEquals(activity.getString(R.string.submit), labelOf(top.getChildAt(1)))

        // 中间三行是 1-9
        val digits = StringBuilder()
        for (i in 1..3) {
            val r = keyboard.getChildAt(i) as android.widget.LinearLayout
            for (j in 0 until r.childCount) {
                digits.append(labelOf(r.getChildAt(j)))
            }
        }
        assertEquals("123456789", digits.toString())

        // 最底行：清空 / 0 / 退格（图标键，无文本）
        val bottom = keyboard.getChildAt(4) as android.widget.LinearLayout
        assertEquals(3, bottom.childCount)
        assertEquals("清空", labelOf(bottom.getChildAt(0)))
        assertEquals("0", labelOf(bottom.getChildAt(1)))
        assertTrue("最底行右侧是退格图标键",
            ((bottom.getChildAt(2) as android.widget.FrameLayout).getChildAt(0)) is android.widget.ImageView)
    }

    @Test
    fun `右手模式整列布局靠右收窄`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val content = activity.findViewById<View>(R.id.panelHost)
        val keyboard = activity.findViewById<PinyinKeyboardView>(R.id.keyboard)
        val screen = activity.resources.displayMetrics.widthPixels

        // 默认居中：整列满宽
        assertEquals(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            content.layoutParams.width
        )

        // 右手模式：整列（顶栏/支付入口/列表/搜索框/键盘）收窄 75% 并贴右
        activity.setKeyboardSide("right")
        val lp = content.layoutParams as android.widget.FrameLayout.LayoutParams
        assertEquals((screen * 0.75f).toInt(), lp.width)
        assertEquals(
            "整列应贴右",
            android.view.Gravity.RIGHT,
            lp.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        )
        // 键盘撑满整列，不再单独收窄（否则会比整列还窄）
        assertEquals(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            keyboard.layoutParams.width
        )

        // 高度收到屏高 82% 再减掉底部留白：顶边落在屏幕 18% 处，不再顶到状态栏
        val screenH = activity.resources.displayMetrics.heightPixels
        val gap = (screenH * 0.04f).toInt()
        assertEquals((screenH * 0.82f).toInt() - gap, lp.height)
        assertEquals("底边应离屏幕底留一截", gap, lp.bottomMargin)
        assertEquals(
            "整列应贴底",
            android.view.Gravity.BOTTOM,
            lp.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
        )

        // 左手模式：整列贴左
        activity.setKeyboardSide("left")
        assertEquals(
            android.view.Gravity.LEFT,
            (content.layoutParams as android.widget.FrameLayout.LayoutParams).gravity and
                android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        )

        // 回到居中：恢复满宽
        activity.setKeyboardSide("center")
        assertEquals(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            content.layoutParams.width
        )
    }

    @Test
    fun `26 键键高按模式区分：单手矮、全屏高`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val kb = activity.findViewById<PinyinKeyboardView>(R.id.keyboard)

        activity.setKeyboardSide("right")
        assertEquals(
            "单手模式用矮胖键高",
            PinyinKeyboardView.QWERTY_H_COMPACT,
            kb.qwertyRowHeightDp
        )

        activity.setKeyboardSide("center")
        assertEquals(
            "全屏模式把键盘抬高",
            PinyinKeyboardView.QWERTY_H_FULL,
            kb.qwertyRowHeightDp
        )
        assertTrue(
            "全屏必须比单手高",
            PinyinKeyboardView.QWERTY_H_FULL > PinyinKeyboardView.QWERTY_H_COMPACT
        )
    }

    @Test
    fun `顶栏握持按钮按 全屏-右-左 循环切换`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val btn = activity.findViewById<android.widget.TextView>(R.id.btnSide)

        assertNotNull("顶栏必须有握持切换按钮", btn)
        assertEquals("默认全屏", "全屏", btn.text.toString())

        btn.performClick()
        assertEquals("第一次点：右单手", "右单手", btn.text.toString())
        assertEquals("状态要落到 prefs", "right", Prefs(activity).keyboardSide)

        btn.performClick()
        assertEquals("第二次点：左单手", "左单手", btn.text.toString())
        assertEquals("状态要落到 prefs", "left", Prefs(activity).keyboardSide)

        btn.performClick()
        assertEquals("第三次点：回到全屏", "全屏", btn.text.toString())
        assertEquals("状态要落到 prefs", "center", Prefs(activity).keyboardSide)
    }

    @Test
    fun `单手模式面板是圆角卡片，居中模式直角铺满`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val panel = activity.findViewById<View>(R.id.panelHost)

        activity.setKeyboardSide("right")
        assertTrue("单手模式要按轮廓裁剪（背景图也要一起圆角）", panel.clipToOutline)
        assertTrue("圆角卡片应有一点投影", panel.elevation > 0f)
        val narrowOutline = android.graphics.Outline()
        panel.outlineProvider?.getOutline(panel, narrowOutline)
        assertTrue("单手模式轮廓应是圆角", narrowOutline.radius > 0f)

        activity.setKeyboardSide("center")
        val fullOutline = android.graphics.Outline()
        panel.outlineProvider?.getOutline(panel, fullOutline)
        assertEquals("居中模式铺满全屏，必须直角", 0f, fullOutline.radius, 0.001f)
        assertEquals("居中模式不要投影", 0f, panel.elevation, 0.001f)
    }

    @Test
    fun `面板外是半透明薄雾可以透出下层`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val outside = activity.findViewById<View>(R.id.outsideTap)

        val bg = outside.background as? android.graphics.drawable.ColorDrawable
        assertNotNull("面板外需要一层薄雾", bg)
        // 必须带 alpha：下面要透出「系统模糊后的桌面」，实心色块会把桌面挡死
        val alpha = (bg!!.color ushr 24) and 0xFF
        assertTrue("薄雾应半透明（当前 alpha=$alpha）", alpha in 1..254)
    }

    @Test
    fun `单手模式点面板外退出软件`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val outside = activity.findViewById<View>(R.id.outsideTap)

        // 居中模式：面板铺满全屏，点「外面」不该退出
        outside.performClick()
        assertFalse("居中模式点空白不应退出", activity.isFinishing)

        // 单手模式：面板只占 75% 宽，面板外是纯透明区，点一下退出
        activity.setKeyboardSide("right")
        outside.performClick()
        assertTrue("单手模式点面板外应退出", activity.isFinishing)
    }

    @Test
    fun `长按浮层挂在面板内部不会越界`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val panel = activity.findViewById<android.view.ViewGroup>(R.id.panelHost)
        val overlay = activity.findViewById<View>(R.id.overlayLayer)

        // 浮层宿主必须是面板的子 View：PopupWindow 是独立窗口，坐标一偏就飘出面板边框
        assertSame("浮层宿主应直接挂在面板里", panel, overlay.parent)
        assertTrue("面板必须裁剪子 View", panel.clipChildren)
        // 面板自己有底色，面板外才能保持纯透明
        assertNotNull("面板需要有背景（操作界面的底色）", panel.background)
    }

    @Test
    fun `应用列表缓存可写入并读回`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PinyinEngine.ensureLoaded(ctx)
        val cache = AppCache(ctx)
        cache.clear()

        // 用本应用自身的包名，保证「已安装」校验能通过
        val entry = AppEntry(
            ctx.packageName,
            "${ctx.packageName}.MainActivity",
            "微信",
            null,
            PinyinEngine.buildVariants("微信")
        )
        cache.write(listOf(entry))

        // 使用次数单独存在 Prefs，读缓存时要补回来
        Prefs(ctx).recordLaunch(ctx.packageName)

        val read = cache.read()
        assertNotNull("缓存应能读回", read)
        assertEquals(1, read!!.size)
        assertEquals("微信", read[0].label)
        assertTrue(
            "拼音变体应随缓存一起恢复: " + read[0].variants.map { it.full },
            read[0].variants.any { it.full == "weixin" }
        )
        assertEquals("使用次数应随缓存恢复", 1, read[0].useCount)

        Prefs(ctx).clearUsage()
        cache.clear()
    }

    @Test
    fun `搜索提示文案更新`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val tvQuery = activity.findViewById<android.widget.TextView>(R.id.tvQuery)
        assertEquals(activity.getString(R.string.search_hint), tvQuery.text.toString())
        assertTrue("提示应说明可输入拼音里的任意字母",
            tvQuery.text.toString().contains("字母") && tvQuery.text.toString().contains("ix"))
    }
}
