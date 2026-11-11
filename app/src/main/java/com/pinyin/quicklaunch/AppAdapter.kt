package com.pinyin.quicklaunch

import android.graphics.Outline
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val showPinyin: () -> Boolean,
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit
) : ListAdapter<SearchEngine.Hit, AppAdapter.VH>(DIFF) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val name: TextView = view.findViewById(R.id.tvName)
        val py: TextView = view.findViewById(R.id.tvPy)
        val count: TextView = view.findViewById(R.id.tvCount)

        init {
            // 应用图标裁成方圆角（≈小米那种框框），别让系统自带的大圆角显得太"圆"
            icon.clipToOutline = true
            icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    val r = v.width * ICON_CORNER_RATIO
                    outline.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
        }

        companion object {
            /** 图标圆角占边长的比例，0.24 接近小米的方圆角观感 */
            private const val ICON_CORNER_RATIO = 0.24f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hit = getItem(position)
        val app = hit.app

        holder.name.text = app.label
        holder.icon.setImageDrawable(app.icon)

        val showPy = showPinyin()
        holder.py.visibility = if (showPy) View.VISIBLE else View.GONE
        if (showPy) {
            val text = if (hit.matched.isNotEmpty()) {
                hit.matched
            } else {
                app.variants.firstOrNull()?.full ?: ""
            }
            holder.py.text = highlightMatch(text, hit)
        }

        holder.count.text = if (app.useCount > 0) "×${app.useCount}" else ""

        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener {
            onLongClick(app, holder.itemView)
            true
        }
    }

    /** 把命中的拼音片段标红加粗；无有效区间时原样返回 */
    private fun highlightMatch(text: String, hit: SearchEngine.Hit): CharSequence {
        val s = hit.matchStart
        val e = hit.matchEnd
        if (s < 0 || e <= s || e > text.length) return text
        val sp = SpannableString(text)
        sp.setSpan(
            ForegroundColorSpan(0xFFFF5A5A.toInt()),
            s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sp.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchEngine.Hit>() {
            override fun areItemsTheSame(a: SearchEngine.Hit, b: SearchEngine.Hit): Boolean =
                a.app.key == b.app.key

            override fun areContentsTheSame(a: SearchEngine.Hit, b: SearchEngine.Hit): Boolean =
                a.app.key == b.app.key &&
                    a.matched == b.matched &&
                    a.matchStart == b.matchStart &&
                    a.matchEnd == b.matchEnd &&
                    a.app.useCount == b.app.useCount &&
                    a.app.label == b.app.label
        }
    }
}
