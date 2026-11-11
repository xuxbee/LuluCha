package com.pinyin.quicklaunch

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pinyin.quicklaunch.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), PinyinKeyboardView.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter

    private var allApps: List<AppEntry> = emptyList()
    private val query = StringBuilder()
    private var loading = true
    private lateinit var pickBackground: ActivityResultLauncher<Array<String>>

    /** 应用列表磁盘缓存：启动先读它，退出界面时再全量扫描回写 */
    private lateinit var cache: AppCache
    @Volatile
    private var refreshing = false
    private var lastQuietRefreshAt = 0L

    /** 单手模式：上次实测出的面板高度（列表暂时为空时沿用，避免高度抖动） */
    private var narrowPanelH = 0

    /** 内容列底部避让（小白条）高度，面板高度计算要用 */
    private var insetBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        repo = AppRepository(this)
        cache = AppCache(this)

        // 沉浸式：状态栏/导航条透明，背景图铺满全屏；
        // 内容列用 systemBars insets 避让（顶部避开状态栏、底部避开小白条）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            insetBottom = bars.bottom
            binding.contentRoot.setPadding(
                0,
                // 单手模式下面板不贴顶，不必再避让状态栏/挖孔
                if (isNarrow()) 0 else bars.top,
                0,
                bars.bottom
            )
            // 拿到真实小白条高度后，把面板高度按「N 个应用框」再校准一次
            fitNarrowPanelHeight()
            insets
        }

        adapter = AppAdapter(
            showPinyin = { prefs.showPinyin },
            onClick = { launchApp(it) },
            onLongClick = { app, _ -> showAppActions(app) }
        )

        binding.rvApps.layoutManager = GridLayoutManager(this, 2)
        binding.rvApps.adapter = adapter
        binding.rvApps.itemAnimator = null
        binding.rvApps.overScrollMode = View.OVER_SCROLL_NEVER
        // 列表首次铺满 / 换字体大小 / 换键盘高度后，重新按「N 个应用框」校准面板高度
        // （高度一致时 fitNarrowPanelHeight 不会重复赋值，不会死循环）
        binding.rvApps.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isNarrow()) fitNarrowPanelHeight()
        }

        pickBackground = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importBackground(uri) }

        binding.keyboard.listener = this
        binding.keyboard.mode = prefs.keyboardMode
        binding.keyboard.uppercase = prefs.keyboardUppercase
        binding.keyboard.heightScale = prefs.keyHeight / 100f
        applyKeyboardSide()
        updateModeLabel()
        applyBackground()
        applyOutsideBlur()

        binding.btnClear.setOnClickListener {
            query.setLength(0)
            refresh()
        }
        binding.tvMode.setOnClickListener { toggleMode() }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnSide.setOnClickListener { cycleSideMode() }

        // 面板外的纯透明区域：点一下就退出。居中模式下面板铺满全屏，
        // outsideTap 被完全盖住，点不到，所以只对单手模式生效。
        binding.outsideTap.setOnClickListener {
            if (isNarrow()) {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }

        // 快捷支付入口
        binding.btnWxScan.setOnClickListener { launchWxShortcut(true) }
        binding.btnWxPay.setOnClickListener { launchWxShortcut(false) }
        binding.btnZfbScan.setOnClickListener {
            launchAlipay("alipayqr://platformapi/startapp?saId=10000007")
        }
        binding.btnZfbPay.setOnClickListener {
            launchAlipay("alipays://platformapi/startapp?appId=20000056")
        }

        refresh()
        loadApps()

        if (!prefs.onboarded) showWelcome()
    }

    /** 首次开屏提醒：离线免费、不碰隐私，说明仅有的两处授权 */
    private fun showWelcome() {
        MaterialAlertDialogBuilder(this)
            .setTitle("欢迎使用噜噜查")
            .setMessage(
                "这个软件完全离线、完全免费，不联网、不上传，" +
                    "不会收集和使用你的任何隐私数据。\n\n" +
                    "使用前有两处需要授权，请放心：\n\n" +
                    "1. 读取应用列表\n" +
                    "    用于搜索和快速启动你手机里的软件，这也是本软件唯一的常驻权限；\n\n" +
                    "2. 选择背景图片\n" +
                    "    仅当你主动更换背景时，通过系统相册选择器读取你选中的那一张，" +
                    "不会扫描、不会保存相册里的其他内容。\n\n" +
                    "以上全部在本机离线完成。祝使用愉快！"
            )
            .setCancelable(false)
            .setPositiveButton("我知道了") { _, _ ->
                prefs.onboarded = true
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateModeLabel()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        query.setLength(0)
        refresh()
    }

    // ------------------------------------------------------------- loading

    /**
     * 启动加载：先读磁盘缓存立刻铺满列表（通常几十毫秒），读不到才全量扫描。
     * 全量扫描挪到退出界面时做（见 [onStop]），这样每次打开都不用干等。
     */
    private fun loadApps() {
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { cache.read() }.getOrNull()
            }
            if (!cached.isNullOrEmpty()) {
                allApps = cached
                loading = false
                refresh()
                return@launch
            }

            // 首次安装、或缓存被清：只能完整扫一遍
            binding.progress.visibility = View.VISIBLE
            val apps = withContext(Dispatchers.IO) {
                runCatching { repo.loadApps() }.getOrElse { emptyList() }
            }
            allApps = apps
            loading = false
            binding.progress.visibility = View.GONE
            refresh()

            if (apps.isEmpty()) {
                toast("没有读到应用，请检查应用列表读取权限")
            } else {
                withContext(Dispatchers.IO) { runCatching { cache.write(apps) } }
            }
        }
    }

    /**
     * 退出界面（切到别的应用 / 按返回 / 回桌面）时后台静默扫一遍，
     * 结果写进缓存，下次打开直接是最新的，不用再等。
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) refreshListQuietly()
    }

    private fun refreshListQuietly() {
        if (refreshing) return
        // 「从噜噜查启动应用 → 返回 → 再启动」时列表几乎不会变，
        // 15 秒内不重复扫，省电；超出后再扫，保证装了新应用能刷出来
        val now = System.currentTimeMillis()
        if (now - lastQuietRefreshAt < QUIET_REFRESH_GAP_MS) return
        lastQuietRefreshAt = now
        refreshing = true
        Thread {
            // 这里已经在后台线程，直接阻塞等待扫描结果（不用 lifecycleScope，
            // 免得用户点开应用、界面销毁时把刷新任务一起取消掉）
            val apps = runCatching { runBlocking { repo.loadApps() } }.getOrNull()
            if (apps.isNullOrEmpty()) {
                refreshing = false
                return@Thread
            }
            runCatching { cache.write(apps) }
            runOnUiThread {
                refreshing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                loading = false
                // 用户此刻已离开界面，只有回到前台且没在输入时才重排，避免打断输入
                if (query.isEmpty()) refresh()
            }
        }.start()
    }

    /** 设置里的「刷新应用列表」：立刻重扫、回写缓存并重排界面 */
    private fun manualRefresh() {
        if (refreshing) {
            toast("正在刷新，请稍候…")
            return
        }
        refreshing = true
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { repo.loadApps() }.getOrElse { emptyList() }
            }
            if (apps.isNotEmpty()) {
                withContext(Dispatchers.IO) { runCatching { cache.write(apps) } }
            }
            binding.progress.visibility = View.GONE
            refreshing = false
            if (apps.isEmpty()) {
                toast("没有读到应用，请检查应用列表读取权限")
            } else {
                allApps = apps
                loading = false
                refresh()
                toast("已刷新，共 ${apps.size} 个应用")
            }
        }
    }

    // ------------------------------------------------------------ rendering

    private fun refresh() {
        val q = query.toString()
        renderQuery(q)
        binding.btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE

        // 列表是倒序的：命中最高的排在末尾，滚动定位到最后一项，
        // 就正好贴在键盘正上方（GridLayoutManager 不支持 stackFromEnd，会抛异常）
        binding.rvApps.stopScroll()
        if (adapter.itemCount > 0) {
            binding.rvApps.scrollToPosition(adapter.itemCount - 1)
        }

        val hits = SearchEngine.search(q, allApps).asReversed()
        adapter.submitList(hits.toList(), Runnable {
            // diff 应用完再兜一次底，保证最佳命中始终在键盘上方第一眼位置
            binding.rvApps.post {
                binding.rvApps.stopScroll()
                if (adapter.itemCount > 0) {
                    binding.rvApps.scrollToPosition(adapter.itemCount - 1)
                }
                // 列表铺好后再按真实行高校准面板高度（只在单手模式下生效）
                fitNarrowPanelHeight()
            }
        })

        val empty = hits.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            binding.tvEmpty.text = when {
                loading -> getString(R.string.loading)
                allApps.isEmpty() -> "没有读到应用\n请确认本应用可以读取已安装应用列表"
                else -> getString(R.string.empty_hint)
            }
        }
    }

    private fun renderQuery(q: String) {
        if (q.isEmpty()) {
            binding.tvQuery.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            // 提示语用 17sp：单手握持（整列收窄到 75%）时也能完整显示，不被裁掉
            binding.tvQuery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            binding.tvQuery.text = getString(R.string.search_hint)
        } else {
            binding.tvQuery.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvQuery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            val sp = SpannableString(q + "▏")
            sp.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.accent)),
                q.length,
                q.length + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.tvQuery.text = sp
        }
    }

    // -------------------------------------------------------------- keyboard

    override fun onChar(c: Char) {
        if (query.length >= 16) return
        query.append(c)
        refresh()
    }

    override fun onBackspace() {
        if (query.isNotEmpty()) {
            query.deleteCharAt(query.length - 1)
            refresh()
        }
    }

    override fun onClear() {
        query.setLength(0)
        refresh()
    }

    override fun onEnter() {
        // 列表倒序后，最佳命中排在末尾（键盘正上方）
        val hit = adapter.currentList.lastOrNull() ?: return
        launchApp(hit.app)
    }

    override fun onModeToggle() = toggleMode()

    override fun onKeyLongPress(key: String, anchor: View) {
        // 已输入内容 + 当前键：先按 7 再长按 7 -> 按 "77" 的候选（QQ 会在这里）
        val prefix = query.toString()
        showQuickPicker(prefix + key, anchor)
    }

    private fun toggleMode() {
        val next = if (binding.keyboard.mode == PinyinKeyboardView.MODE_T9) {
            PinyinKeyboardView.MODE_QWERTY
        } else {
            PinyinKeyboardView.MODE_T9
        }
        binding.keyboard.mode = next
        prefs.keyboardMode = next
        updateModeLabel()
    }

    private fun updateModeLabel() {
        binding.tvMode.text =
            if (prefs.keyboardMode == PinyinKeyboardView.MODE_T9) "T9" else "26键"
    }

    /** 是否单手（左右侧边）模式 */
    private fun isNarrow(): Boolean {
        val side = prefs.keyboardSide
        return side == "right" || side == "left"
    }

    /**
     * 键盘位置 / 单手握持：
     * - center：整页满宽满高；
     * - right / left：整列（顶栏、支付入口、结果列表、搜索框、键盘）一起
     *   收窄到屏宽 75%，高度收到屏高 3/4 并贴底部，整块落到拇指活动区，
     *   顶栏不再顶在状态栏下面。
     */
    /**
     * 顶栏的握持切换：全屏 → 右单手 → 左单手 → 全屏，点一下换一种。
     * 和设置里的「键盘位置」是同一个值（prefs.keyboardSide），两边互相同步。
     */
    private fun cycleSideMode() {
        val next = when (prefs.keyboardSide) {
            "right" -> "left"
            "left" -> "center"
            else -> "right"
        }
        setKeyboardSide(next)
    }

    /** 按钮文字直接显示当前握持方式，一眼能看出现在是哪种 */
    private fun updateSideLabel() {
        binding.btnSide.text = when (prefs.keyboardSide) {
            "right" -> "右单手"
            "left" -> "左单手"
            else -> "全屏"
        }
    }

    fun setKeyboardSide(side: String) {
        prefs.keyboardSide = side
        // 面板尺寸变了，浮层的相对坐标会失效，直接收起
        hideQuickPicker()
        applyKeyboardSide()
    }

    fun applyKeyboardSide() {
        val side = prefs.keyboardSide
        val narrow = isNarrow()
        updateSideLabel()

        // 0) 26 键键高按模式走：单手矮胖（40dp），全屏抬高（48dp）。
        //    键盘重建后高度要等下一帧才生效，所以变了就再跑一遍（值相同会被 setter 挡掉，不会死循环）。
        val wantH = if (narrow) {
            PinyinKeyboardView.QWERTY_H_COMPACT
        } else {
            PinyinKeyboardView.QWERTY_H_FULL
        }
        val wantGap = if (narrow) {
            PinyinKeyboardView.QWERTY_GAP_COMPACT
        } else {
            PinyinKeyboardView.QWERTY_GAP_FULL
        }
        val keySizeChanged = binding.keyboard.qwertyRowHeightDp != wantH ||
            binding.keyboard.qwertyRowGapDp != wantGap
        binding.keyboard.qwertyRowHeightDp = wantH
        binding.keyboard.qwertyRowGapDp = wantGap

        val apply = fun(rootW: Int, rootH: Int) {
            if (rootW <= 0 || rootH <= 0) return

            // 1) 整列尺寸、贴边方向与垂直落位
            val clp = binding.panelHost.layoutParams
            if (clp is FrameLayout.LayoutParams) {
                clp.topMargin = 0
                clp.bottomMargin = 0
                if (narrow) {
                    // 底边抬起一截，别贴着小白条；高度按「正好放下 N 个应用框」实测
                    val gap = (rootH * PANEL_BOTTOM_GAP_RATIO).toInt()
                    clp.width = (rootW * NARROW_RATIO).toInt()
                    clp.height = narrowPanelHeight(rootH, gap)
                    clp.bottomMargin = gap
                    clp.gravity =
                        (if (side == "right") Gravity.END else Gravity.START) or Gravity.BOTTOM
                } else {
                    clp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    clp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    clp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                }
                binding.panelHost.layoutParams = clp
            }

            // 2) 圆角/投影：单手模式是浮起的圆角卡片，居中模式必须直角铺满
            applyPanelCorners(narrow)

            // 2) 面板不再贴顶，顶部避让要跟着变（见 insets 回调）
            ViewCompat.requestApplyInsets(binding.root)
        }

        if (binding.root.width > 0 && binding.root.height > 0) {
            apply(binding.root.width, binding.root.height)
        } else {
            // 首帧前拿不到根布局尺寸：先按整屏尺寸生效，布局完成后再按真实尺寸校准
            val dm = resources.displayMetrics
            apply(dm.widthPixels, dm.heightPixels)
            binding.root.doOnLayout {
                apply(binding.root.width, binding.root.height)
            }
        }

        // 键盘撑满所在列（整列已经收窄，键盘不需要再单独设宽度）
        val klp = binding.keyboard.layoutParams as LinearLayout.LayoutParams
        klp.width = ViewGroup.LayoutParams.MATCH_PARENT
        klp.gravity = Gravity.CENTER_HORIZONTAL
        binding.keyboard.layoutParams = klp

        // 键高刚变过：键盘实际高度要等它重新布局，之后再按新高度校准面板
        if (keySizeChanged) {
            binding.keyboard.post { applyKeyboardSide() }
        }
    }

    /**
     * 面板圆角：单手模式下把「缩小的那块操作界面」裁成圆角卡片（背景图、列表、键盘
     * 一起裁），视觉上不再是一个直角硬切的方块；居中模式铺满全屏，必须保持直角，
     * 否则四角会漏出外面的模糊层。
     *
     * 用 outline 裁剪而不是换成圆角背景：面板里还有背景图 ivBg，只有 clipToOutline
     * 能把它一起裁圆；圆角背景只能改底色，图片照样是直角。
     */
    private fun applyPanelCorners(narrow: Boolean) {
        val radius = if (narrow) dp(PANEL_CORNER_DP).toFloat() else 0f
        binding.panelHost.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (radius <= 0f) {
                    outline.setRect(0, 0, view.width, view.height)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
        binding.panelHost.clipToOutline = true
        // 有轮廓才有投影：卡片浮在模糊背景上，边界更柔
        binding.panelHost.elevation = if (narrow) dp(PANEL_ELEVATION_DP).toFloat() else 0f
        binding.panelHost.invalidateOutline()
    }

    /**
     * 单手模式的面板高度：按「列表正好露出 [PANEL_VISIBLE_ITEMS] 个应用框」实测计算，
     * 而不是按屏高比例硬切 —— 比例切法常把最后一行切掉一半，看着像被裁。
     * 顶栏 / 快捷入口 / 搜索框 / 键盘都取实测高度，字号、键盘高度变了也能自适应。
     */
    private fun narrowPanelHeight(rootH: Int, gap: Int): Int {
        val fallback = (rootH * PANEL_HEIGHT_RATIO).toInt() - gap
        val first = if (binding.rvApps.childCount > 0) binding.rvApps.getChildAt(0) else null
        if (first == null || first.height <= 0) {
            // 列表空着（搜索无结果 / 还没扫完）时沿用上次值，避免面板高度来回跳
            return if (narrowPanelH > 0) narrowPanelH else fallback
        }

        val mlp = first.layoutParams as? ViewGroup.MarginLayoutParams
        val rowH = first.height + (mlp?.topMargin ?: 0) + (mlp?.bottomMargin ?: 0)
        val span = (binding.rvApps.layoutManager as? GridLayoutManager)?.spanCount ?: 2
        // 结果不足 4 个时按实际个数收（键盘位置不变，只是面板顶边下移），免得留一大块空白
        val got = binding.rvApps.adapter?.itemCount ?: 0
        val want = if (got in 1 until PANEL_VISIBLE_ITEMS) got else PANEL_VISIBLE_ITEMS
        val rows = (want + span - 1) / span
        val listH = binding.rvApps.paddingTop + binding.rvApps.paddingBottom + rowH * rows

        val fixed = blockHeight(binding.headerBar) +
            blockHeight(binding.payRow) +
            blockHeight(binding.searchBar) +
            blockHeight(binding.keyboard)

        // insetBottom 是内容列底部的避让（小白条），也算进面板高度；再兜个不超屏的上限
        narrowPanelH = (insetBottom + fixed + listH).coerceAtMost(rootH - gap)
        return narrowPanelH
    }

    /** 子 View 连同上下外边距的占位高度 */
    private fun blockHeight(v: View): Int {
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
        return v.height + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
    }

    /** insets 回来（拿到真实小白条高度）后重算一次；不重新请求 insets，避免回调互相触发 */
    private fun fitNarrowPanelHeight() {
        if (!isNarrow()) return
        val clp = binding.panelHost.layoutParams as? FrameLayout.LayoutParams ?: return
        val rootH =
            if (binding.root.height > 0) binding.root.height else resources.displayMetrics.heightPixels
        val target = narrowPanelHeight(rootH, clp.bottomMargin)
        if (target > 0 && clp.height != target) {
            clp.height = target
            binding.panelHost.layoutParams = clp
        }
    }

    // -------------------------------------------------------------- launching

    /** 微信快捷入口：扫一扫(true) / 付款码(false) */
    private fun launchWxShortcut(scan: Boolean) {
        try {
            val i = Intent("com.tencent.mm.action.BIZSHORTCUT").apply {
                setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
                if (scan) {
                    // 扫一扫：老通道仍有效
                    putExtra("LauncherUI.From.Scaner.Shortcut", true)
                } else {
                    // 付款码：新版微信只认自建快捷方式的 LaunchType 通道，
                    // 老参数 From.Scaner.Shortcut=false 已被忽略（表现为只进主界面）
                    putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_offline_wallet")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
        } catch (t: Throwable) {
            toast("未安装微信，或微信未开放该入口")
        }
    }

    /** 支付宝快捷入口：公开 deeplink（扫一扫 / 付款码） */
    private fun launchAlipay(deeplink: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(deeplink))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            toast("未安装支付宝，或支付宝未开放该入口")
        }
    }

    private fun launchApp(app: AppEntry) {
        val intent = repo.launchIntentFor(app.packageName)
        if (intent == null) {
            toast("无法启动「${app.label}」")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            prefs.recordLaunch(app.packageName)
            app.useCount = prefs.useCount(app.packageName)
            app.lastUsed = prefs.lastUsed(app.packageName)
            query.setLength(0)
            refresh()
        } catch (t: Throwable) {
            toast("启动失败：${t.message}")
        }
    }

    // ------------------------------------------------------- long-press picker

    /** 长按键盘键：浮出「已输入内容 + 该键」对应的应用，点一下直接启动 */
    private fun showQuickPicker(fullKey: String, anchor: View) {
        // 直接复用完整搜索打分：前缀、子串、松散匹配都会参与，
        // 这样叫「手机QQ」这类名字也能在按 77 时被捞出来
        val apps = SearchEngine.search(fullKey, allApps, 12).map { it.app }
        if (apps.isEmpty()) {
            toast("没有匹配「$fullKey」的应用")
            return
        }

        val layer = binding.overlayLayer
        layer.removeAllViews()
        layer.visibility = View.VISIBLE
        layer.setOnClickListener { hideQuickPicker() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val header = TextView(this)
        header.text = "$fullKey · ${apps.size} 个"
        header.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        header.letterSpacing = 0.10f
        header.setPadding(dp(10), dp(2), dp(10), dp(6))
        content.addView(header)

        apps.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 行高收矮（8→6dp），条目多时靠滚动看，不再把浮层撑高
                setPadding(dp(10), dp(6), dp(16), dp(6))
                isClickable = true
                background = selectableItemBackground()
            }

            val icon = ImageView(this)
            icon.setImageDrawable(app.icon)
            row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))

            val name = TextView(this)
            name.text = app.label
            name.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(10)
            row.addView(name, lp)

            row.setOnClickListener {
                hideQuickPicker()
                launchApp(app)
            }
            content.addView(row)
        }

        val scroll = ScrollView(this).apply {
            addView(content)
            isVerticalScrollBarEnabled = false
            background = roundedBg(R.color.bg_elevated, 12, R.color.divider)
            // 卡片自己也消费触摸：点卡片上的空白不会冒泡到 layer 被当成「点外面」
            isClickable = true
        }

        /*
         * 浮层直接画在 panelHost 内部（overlayLayer），不再用 PopupWindow。
         * PopupWindow 是独立窗口，坐标一旦算偏就会跑到面板边框外面；
         * 现在交给 panelHost 的 clipChildren，物理上不可能超出面板。
         */
        val aLoc = IntArray(2)
        anchor.getLocationOnScreen(aLoc)
        val lLoc = IntArray(2)
        layer.getLocationOnScreen(lLoc)
        val panelW = (layer.width.takeIf { it > 0 } ?: binding.panelHost.width)
            .coerceAtLeast(dp(200))
        val panelH = (layer.height.takeIf { it > 0 } ?: binding.panelHost.height)
            .coerceAtLeast(dp(200))
        val anchorX = aLoc[0] - lLoc[0]
        val anchorY = aLoc[1] - lLoc[1]

        val gap = dp(8)
        val maxW = (panelW - gap * 2).coerceAtMost(dp(280))

        // 先不限高量完整内容高度，再把高度压进「锚点上方可用空间」，超出部分靠滑动
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = scroll.measuredWidth
        val contentH = scroll.measuredHeight
        val availH = (anchorY - gap * 2).coerceAtLeast(dp(96))
        val h = minOf(contentH, availH, dp(320))

        val maxX = (panelW - w - gap).coerceAtLeast(gap)
        val x = (anchorX + anchor.width / 2 - w / 2).coerceIn(gap, maxX)
        val maxY = (panelH - h - gap).coerceAtLeast(gap)
        val y = (anchorY - h - gap).coerceIn(gap, maxY)

        layer.addView(
            scroll,
            FrameLayout.LayoutParams(w, h).apply {
                leftMargin = x
                topMargin = y
            }
        )
        // 始终从最佳匹配（第一条）开始显示
        scroll.scrollTo(0, 0)
    }

    private fun hideQuickPicker() {
        binding.overlayLayer.removeAllViews()
        binding.overlayLayer.visibility = View.GONE
    }

    // --------------------------------------------------------- app long press

    /** 长按应用：优先列出它的快捷方式，读不到则降级为应用信息/卸载/分享 */
    private fun showAppActions(app: AppEntry) {
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(18))
        }

        // 头部
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        val bigIcon = ImageView(this)
        bigIcon.setImageDrawable(app.icon)
        header.addView(bigIcon, LinearLayout.LayoutParams(dp(46), dp(46)))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val title = TextView(this)
        title.text = app.label
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        val sub = TextView(this)
        sub.text = app.packageName
        sub.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        textCol.addView(title)
        textCol.addView(sub)
        header.addView(textCol)
        container.addView(header)

        container.addView(sectionLabel("应用"))
        container.addView(actionRow(null, "应用信息") {
            sheet.dismiss()
            openAppInfo(app.packageName)
        })
        container.addView(actionRow(null, "分享应用名") {
            sheet.dismiss()
            shareApp(app)
        })
        container.addView(actionRow(null, "分享安装包") {
            sheet.dismiss()
            shareApk(app)
        })
        container.addView(actionRow(null, "卸载") {
            sheet.dismiss()
            try {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            } catch (t: Throwable) {
                toast("无法打开卸载界面")
            }
        })

        val scroll = ScrollView(this).apply { addView(container) }
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun sectionLabel(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.letterSpacing = 0.12f
        tv.setPadding(dp(20), dp(14), dp(20), dp(4))
        return tv
    }

    private fun hintRow(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setLineSpacing(dp(3).toFloat(), 1f)
        tv.setPadding(dp(20), dp(8), dp(20), dp(8))
        return tv
    }

    private fun actionRow(icon: Drawable?, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(13), dp(20), dp(13))
            isClickable = true
            background = selectableItemBackground()
        }

        if (icon != null) {
            val iv = ImageView(this)
            iv.setImageDrawable(icon)
            row.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)))
        }

        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (icon != null) lp.marginStart = dp(14)
        row.addView(tv, lp)

        row.setOnClickListener { onClick() }
        return row
    }

    // ---------------------------------------------------------------- settings

    // ---------------------------------------------------------- album background

    private fun bgFile() = java.io.File(filesDir, "background.jpg")

    /**
     * 导入相册图片：
     * 1. ACTION_OPEN_DOCUMENT 返回的 URI 可持久化授权，比 ACTION_GET_CONTENT 在
     *    部分国产 ROM（小米澎湃等）上稳定得多；
     * 2. 只打开一次流，整体复制到私有目录，避免「第一次读取成功、第二次失败」；
     * 3. 解码在 IO 线程进行，大图不会卡住界面。
     */
    private fun importBackground(uri: Uri) {
        // OpenDocument 保证可持久化；拿不到也不影响本次读取，静默忽略
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = java.io.File(filesDir, "bg_tmp")
            try {
                val copied = contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                    tmp.length()
                }
                if (copied == null || copied == 0L) {
                    withContext(Dispatchers.Main) {
                        toast("图片拉取失败，请换一张已下载到本机的图片")
                    }
                    tmp.delete()
                    return@launch
                }

                val bmp = decodeScaled(tmp)
                if (bmp == null) {
                    withContext(Dispatchers.Main) {
                        toast("图片解码失败，请换一张图片试试")
                    }
                    tmp.delete()
                    return@launch
                }

                bgFile().outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                tmp.delete()
                bmp.recycle()

                withContext(Dispatchers.Main) {
                    applyBackground()
                    toast("背景已更换")
                }
            } catch (e: SecurityException) {
                tmp.delete()
                withContext(Dispatchers.Main) {
                    toast("没有读取该图片的权限，请重新选择一次")
                }
            } catch (t: Throwable) {
                tmp.delete()
                withContext(Dispatchers.Main) {
                    toast("拉取失败：${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun decodeScaled(f: java.io.File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1600 || bounds.outHeight / (sample * 2) >= 1600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    private fun applyBackground() {
        val f = bgFile()
        if (!f.exists()) {
            binding.ivBg.setImageDrawable(null)
            binding.ivBg.visibility = View.GONE
            return
        }
        runCatching {
            binding.ivBg.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            binding.ivBg.imageAlpha = (prefs.bgAlpha * 255) / 100
            binding.ivBg.visibility = View.VISIBLE
        }.onFailure { binding.ivBg.visibility = View.GONE }
    }

    private fun clearBackground() {
        bgFile().delete()
        applyBackground()
        toast("已移除背景图")
    }

    /**
     * 面板外「透明模糊」：Android 12+ 可以请系统把窗口背后的内容（桌面）做高斯模糊，
     * 再叠上 outsideTap 那层 15% 白雾，就是毛玻璃的感觉。
     *
     * 系统不支持跨窗口模糊（部分 ROM 会关掉）时静默跳过：
     * 面板外就退化成「薄雾 + 清晰桌面」，也不难看，不会崩。
     */
    private fun applyOutsideBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val wm = runCatching {
            getSystemService(android.view.WindowManager::class.java)
        }.getOrNull() ?: return
        if (!runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)) return
        runCatching {
            val lp = window.attributes
            lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            window.attributes = lp
            window.setBackgroundBlurRadius(dp(BLUR_RADIUS_DP))
        }
    }

    private fun showBgAlphaDialog() {
        if (!bgFile().exists()) {
            toast("请先在「背景图片」中选择一张照片")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(4))
        }

        val label = TextView(this).apply {
            text = "${prefs.bgAlpha}%"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END
        }
        container.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val seek = SeekBar(this).apply {
            max = 100
            progress = prefs.bgAlpha
        }
        container.addView(
            seek,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        val hint = TextView(this).apply {
            text = "往左调，背景更透、更暗；往右调，背景更清晰"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        container.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "$p%"
                binding.ivBg.imageAlpha = (p * 255) / 100
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("背景不透明度")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                prefs.bgAlpha = seek.progress
            }
            .setNegativeButton("取消") { _, _ -> applyBackground() }
            .show()
    }

    private fun showSettings() {
        val hasBg = bgFile().exists()
        val sideName = when (prefs.keyboardSide) {
            "right" -> "靠右（右手）"; "left" -> "靠左（左手）"; else -> "居中"
        }
        val items = arrayOf(
            "键盘布局：${if (prefs.keyboardMode == PinyinKeyboardView.MODE_T9) "九宫格 T9" else "26 键"}",
            "字母大小写：${if (prefs.keyboardUppercase) "大写" else "小写"}",
            "键盘高度：${when (prefs.keyHeight) { 85 -> "低"; 115 -> "高"; else -> "标准" }}",
            "键盘位置：$sideName",
            "结果显示拼音：${if (prefs.showPinyin) "开" else "关"}",
            "背景图片：${if (hasBg) "更换 / 移除" else "从相册选择"}",
            "背景不透明度：${prefs.bgAlpha}%",
            "刷新应用列表",
            "清除启动次数记录",
            "关于噜噜查"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleMode()
                    1 -> {
                        prefs.keyboardUppercase = !prefs.keyboardUppercase
                        binding.keyboard.uppercase = prefs.keyboardUppercase
                        binding.keyboard.build()
                    }
                    2 -> showKeyHeightDialog()
                    3 -> showKeySideDialog()
                    4 -> {
                        prefs.showPinyin = !prefs.showPinyin
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    }
                    5 -> showBackgroundPicker(hasBg)
                    6 -> showBgAlphaDialog()
                    7 -> manualRefresh()
                    8 -> {
                        prefs.clearUsage()
                        allApps.forEach {
                            it.useCount = 0
                            it.lastUsed = 0L
                        }
                        refresh()
                        toast("已清除")
                    }
                    9 -> showAbout()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showKeySideDialog() {
        val names = arrayOf("居中", "靠右（右手模式）", "靠左（左手模式）")
        val values = arrayOf("center", "right", "left")
        val checked = values.indexOf(prefs.keyboardSide).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("键盘位置")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                setKeyboardSide(values[which])
                dialog.dismiss()
                toast(if (values[which] == "center") "键盘已居中" else "键盘已靠${if (values[which] == "right") "右" else "左"}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showKeyHeightDialog() {
        val names = arrayOf("低（省屏幕）", "标准", "高（好按）")
        val values = intArrayOf(85, 100, 115)
        val checked = values.indexOf(prefs.keyHeight).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("键盘高度")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                prefs.keyHeight = values[which]
                binding.keyboard.heightScale = values[which] / 100f
                binding.keyboard.build()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBackgroundPicker(hasBg: Boolean) {
        if (!hasBg) {
            pickBackground.launch(arrayOf("image/*"))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("背景图片")
            .setItems(arrayOf("更换图片", "移除背景")) { _, which ->
                if (which == 0) pickBackground.launch(arrayOf("image/*")) else clearBackground()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAbout() {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) { "?" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body) + "\n\n当前版本 v$ver")
            .setPositiveButton("好的", null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            )
        } catch (t: Throwable) {
            toast("无法打开应用信息")
        }
    }

    private fun shareApp(app: AppEntry) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "推荐应用：${app.label}（${app.packageName}）")
        }
        runCatching { startActivity(Intent.createChooser(send, "分享")) }
    }

    /** 提取应用安装包并通过系统分享发出（复制到私有缓存目录，无需存储权限） */
    private fun shareApk(app: AppEntry) {
        toast("正在提取安装包…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val ai = packageManager.getApplicationInfo(app.packageName, 0)
                val src = ai.sourceDir?.let { java.io.File(it) }
                if (src == null || !src.exists()) {
                    withContext(Dispatchers.Main) { toast("找不到安装包文件") }
                    return@launch
                }

                val dir = java.io.File(cacheDir, "apk_share").apply { deleteRecursively(); mkdirs() }
                val safeName = app.label.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                val out = java.io.File(dir, "$safeName.apk")
                if (!out.exists() || out.length() != src.length()) {
                    src.inputStream().use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }

                val uri = FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.fileprovider", out
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    runCatching { startActivity(Intent.createChooser(send, "分享安装包")) }
                        .onFailure { toast("无法分享安装包") }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { toast("提取安装包失败：${t.message}") }
            }
        }
    }

    private fun selectableItemBackground(): Drawable? {
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return ContextCompat.getDrawable(this, out.resourceId)
    }

    private fun roundedBg(colorRes: Int, radiusDp: Int, strokeRes: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, colorRes))
            setStroke(dp(1), ContextCompat.getColor(this@MainActivity, strokeRes))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        when {
            // 浮层开着时，返回键先收浮层
            binding.overlayLayer.visibility == View.VISIBLE -> hideQuickPicker()
            query.isNotEmpty() -> {
                query.setLength(0)
                refresh()
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private companion object {
        /** 右手/左手模式下，整列占屏宽的比例 */
        const val NARROW_RATIO = 0.75f

        /**
         * 单手模式：列表要完整露出的应用个数（默认双列 → 6 个 = 3 行）。
         * 面板高度按它实测，不用屏高比例硬切，避免最后一行被切一半。
         */
        const val PANEL_VISIBLE_ITEMS = 6

        /** 单手模式：列表还没有数据时的兜底高度（占屏高比例），正常态以实测为准 */
        const val PANEL_HEIGHT_RATIO = 0.82f

        /** 右手/左手模式下，面板底边离屏幕底的留白（占屏高比例） */
        const val PANEL_BOTTOM_GAP_RATIO = 0.04f

        /** 退出界面时的静默刷新节流间隔 */
        const val QUIET_REFRESH_GAP_MS = 15_000L

        /** 单手模式面板的圆角半径（dp），0 表示直角（居中模式） */
        const val PANEL_CORNER_DP = 24

        /** 单手模式面板的投影高度（dp），让卡片在模糊背景上有一点浮起感 */
        const val PANEL_ELEVATION_DP = 6

        /** 面板外背景模糊半径（dp，Android 12+ 的系统级窗口模糊） */
        const val BLUR_RADIUS_DP = 24
    }
}
