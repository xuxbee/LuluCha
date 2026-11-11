package com.pinyin.quicklaunch

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 自绘键盘，两种面板：
 *   MODE_T9     —— 九宫格拨号盘（数字键做 T9 模糊匹配）+ 提交键
 *   MODE_QWERTY —— 26 键字母盘
 *
 * 所有字母/数字键都支持长按，抛出 onKeyLongPress，由外部弹出「该键开头的应用」浮层。
 */
class PinyinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onChar(c: Char)
        fun onBackspace()
        fun onClear()
        fun onEnter()
        fun onModeToggle()
        fun onKeyLongPress(key: String, anchor: View)
    }

    var listener: Listener? = null

    /** 26 键字母显示为大写（仅影响显示，搜索本身不区分大小写） */
    var uppercase: Boolean = false

    /** 行高缩放：0.85 / 1.0 / 1.15，由设置里的「键盘高度」控制 */
    var heightScale: Float = 1f

    /**
     * 26 键每行高度（dp）。单手模式用矮胖款（[QWERTY_H_COMPACT]），
     * 全屏模式屏幕宽、键也宽，太矮会显得空，用高一点的 [QWERTY_H_FULL]。
     * 值变了且当前就在 26 键面板时才重建，避免无谓的闪动。
     */
    var qwertyRowHeightDp: Int = QWERTY_H_COMPACT
        set(value) {
            if (field == value) return
            field = value
            if (mode == MODE_QWERTY) build()
        }

    /** 26 键行间距（dp）：单手 2，全屏 3，跟着行高一起放大 */
    var qwertyRowGapDp: Int = QWERTY_GAP_COMPACT
        set(value) {
            if (field == value) return
            field = value
            if (mode == MODE_QWERTY) build()
        }

    var mode: Int = MODE_T9
        set(value) {
            if (field == value) return
            field = value
            build()
        }

    init {
        orientation = VERTICAL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (childCount == 0) build()
    }

    fun build() {
        removeAllViews()
        if (mode == MODE_T9) buildT9() else buildQwerty()
    }

    // ---------------------------------------------------------------- T9

    private fun buildT9() {
        val rows = listOf(
            listOf(Triple("1", null, '1'), Triple("2", "abc", '2'), Triple("3", "def", '3')),
            listOf(Triple("4", "ghi", '4'), Triple("5", "jkl", '5'), Triple("6", "mno", '6')),
            listOf(Triple("7", "pqrs", '7'), Triple("8", "tuv", '8'), Triple("9", "wxyz", '9'))
        )

        // 第一行：26键切换 / 提交（提交移到上方，退格换到底行）
        val toggle = makeKey("26键", null, null, 13f, accent = false, special = true)
        toggle.setOnClickListener { listener?.onModeToggle() }

        val enter = makeKey(getString(R.string.submit), null, null, 15f, accent = true, special = false)
        enter.setOnClickListener { listener?.onEnter() }

        addRow(listOf(toggle to 1f, enter to 1f), 42, 0)

        // 中间：1-9 数字键
        rows.forEach { row ->
            val keys = row.map { (num, letters, ch) ->
                val key = makeKey(num, letters, null, 19f, accent = false, special = false)
                key.setOnClickListener { listener?.onChar(ch) }
                key.setOnLongClickListener {
                    listener?.onKeyLongPress(num, key)
                    true
                }
                key to 1f
            }
            addRow(keys, 45, 5)
        }

        // 最底行：清空 / 0 / 退格（退格放拇指最容易按的位置，支持长按连删）
        val clear = makeKey("清空", null, null, 13f, accent = false, special = true)
        clear.setOnClickListener { listener?.onClear() }

        val zero = makeKey("0", null, null, 19f, accent = false, special = false)
        zero.setOnClickListener { listener?.onChar('0') }
        zero.setOnLongClickListener {
            listener?.onKeyLongPress("0", zero)
            true
        }

        val back = makeKey(null, null, R.drawable.ic_backspace, 18f, accent = false, special = true)
        bindBackspace(back)

        addRow(listOf(clear to 1f, zero to 1f, back to 1f), 45, 5)
    }

    // ------------------------------------------------------------- QWERTY

    private fun buildQwerty() {
        // 数字行：1-0，与字母键同高同字号
        val numRow = "1234567890".map { c ->
            val key = makeKey(c.toString(), null, null, 18f, accent = false, special = false)
            bindLetterKey(key, c)
            key to 1f
        }
        addRow(numRow, qwertyRowHeightDp, 0, QWERTY_KEY_GAP)

        val upper = uppercase
        fun label(c: Char) = if (upper) c.uppercaseChar().toString() else c.toString()

        val row1 = "qwertyuiop".map { c ->
            val key = makeKey(label(c), null, null, 18f, accent = false, special = false)
            bindLetterKey(key, c)
            key to 1f
        }
        addRow(row1, qwertyRowHeightDp, qwertyRowGapDp, QWERTY_KEY_GAP)

        val row2 = ArrayList<Pair<View, Float>>()
        row2.add(spacer() to 0.5f)
        for (c in "asdfghjkl") {
            val key = makeKey(label(c), null, null, 18f, accent = false, special = false)
            bindLetterKey(key, c)
            row2.add(key to 1f)
        }
        row2.add(spacer() to 0.5f)
        addRow(row2, qwertyRowHeightDp, qwertyRowGapDp, QWERTY_KEY_GAP)

        val row3 = ArrayList<Pair<View, Float>>()
        row3.add(spacer() to 1.5f)
        for (c in "zxcvbnm") {
            val key = makeKey(label(c), null, null, 18f, accent = false, special = false)
            bindLetterKey(key, c)
            row3.add(key to 1f)
        }
        val back = makeKey(null, null, R.drawable.ic_backspace, 18f, accent = false, special = true)
        bindBackspace(back)
        row3.add(back to 1.5f)
        addRow(row3, qwertyRowHeightDp, qwertyRowGapDp, QWERTY_KEY_GAP)

        val toggle = makeKey("T9", null, null, 13f, accent = false, special = true)
        toggle.setOnClickListener { listener?.onModeToggle() }

        val clear = makeKey("清空", null, null, 13f, accent = false, special = true)
        clear.setOnClickListener { listener?.onClear() }

        val enter = makeKey(getString(R.string.submit), null, null, 15f, accent = true, special = false)
        enter.setOnClickListener { listener?.onEnter() }

        addRow(
            listOf(toggle to 1.8f, clear to 3.2f, enter to 5f),
            qwertyRowHeightDp, qwertyRowGapDp, QWERTY_KEY_GAP
        )
    }

    private fun bindLetterKey(key: View, c: Char) {
        key.setOnClickListener { listener?.onChar(c) }
        key.setOnLongClickListener {
            listener?.onKeyLongPress(c.toString(), key)
            true
        }
    }

    // --------------------------------------------------------- backspace repeat

    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    /** 退格键统一绑定：单击删一个，按住不放持续删除 */
    private fun bindBackspace(key: View) {
        key.setOnClickListener { listener?.onBackspace() }
        key.setOnLongClickListener {
            startRepeatDelete()
            true
        }
        // 松手 / 滑出按键区域时停止连删（不消费事件，单击逻辑照常）
        key.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> stopRepeatDelete()
            }
            false
        }
    }

    private fun startRepeatDelete() {
        listener?.onBackspace() // 长按先删掉一个
        val r = object : Runnable {
            override fun run() {
                listener?.onBackspace()
                repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = r
        repeatHandler.postDelayed(r, REPEAT_DELAY_MS)
    }

    private fun stopRepeatDelete() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeatDelete()
    }

    // -------------------------------------------------------------- pieces

    private fun addRow(
        keys: List<Pair<View, Float>>,
        heightDp: Int,
        topMarginDp: Int,
        keyGapDp: Float = 3f
    ) {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        val scaled = (heightDp * heightScale).toInt().coerceAtLeast(24)
        val rowLp = LayoutParams(LayoutParams.MATCH_PARENT, dp(scaled))
        rowLp.topMargin = dp(topMarginDp)
        row.layoutParams = rowLp

        for ((view, weight) in keys) {
            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
            // 水平间隙越小，键越宽（「胖一点」全靠它）
            val gap = (keyGapDp * resources.displayMetrics.density + 0.5f).toInt()
            lp.marginStart = gap
            lp.marginEnd = gap
            row.addView(view, lp)
        }
        addView(row)
    }

    private fun spacer(): View {
        val v = View(context)
        v.isClickable = false
        return v
    }

    private fun makeKey(
        primary: String?,
        sub: String?,
        iconRes: Int?,
        textSizeSp: Float,
        accent: Boolean,
        special: Boolean
    ): FrameLayout {
        val key = FrameLayout(context)
        key.background = ContextCompat.getDrawable(
            context,
            when {
                accent -> R.drawable.bg_key_accent
                special -> R.drawable.bg_key_special
                else -> R.drawable.bg_key
            }
        )
        key.isClickable = true
        key.isFocusable = true

        val primaryColor = ContextCompat.getColor(
            context,
            if (accent) android.R.color.white else R.color.text_primary
        )

        when {
            iconRes != null -> {
                val iv = ImageView(context)
                iv.setImageResource(iconRes)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                key.addView(
                    iv,
                    FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                )
            }

            sub.isNullOrEmpty() -> {
                val tv = TextView(context)
                tv.text = primary ?: ""
                tv.gravity = Gravity.CENTER
                tv.setTextColor(primaryColor)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                key.addView(
                    tv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
            }

            else -> {
                val col = LinearLayout(context)
                col.orientation = VERTICAL
                col.gravity = Gravity.CENTER

                val main = TextView(context)
                main.text = primary ?: ""
                main.gravity = Gravity.CENTER
                main.setTextColor(primaryColor)
                main.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)

                val subView = TextView(context)
                subView.text = sub
                subView.gravity = Gravity.CENTER
                subView.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                subView.letterSpacing = 0.10f

                col.addView(main)
                col.addView(subView)

                key.addView(
                    col,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
            }
        }
        return key
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun getString(resId: Int): String = context.getString(resId)

    companion object {
        const val MODE_T9 = 0
        const val MODE_QWERTY = 1

        /** 长按退格：立即删一个，随后每 55ms 连删 */
        private const val REPEAT_DELAY_MS = 200L
        private const val REPEAT_INTERVAL_MS = 55L

        /** 26 键每行高度：单手模式（矮胖，拇指好按） */
        const val QWERTY_H_COMPACT = 40

        /** 26 键每行高度：全屏模式（屏幕宽、键也宽，抬高一点才不显空） */
        const val QWERTY_H_FULL = 48

        /** 26 键行间距：单手模式 */
        const val QWERTY_GAP_COMPACT = 2

        /** 26 键行间距：全屏模式 */
        const val QWERTY_GAP_FULL = 3

        /** 26 键左右间隙（dp）：越小键越宽，两种模式共用 */
        const val QWERTY_KEY_GAP = 2f
    }
}
