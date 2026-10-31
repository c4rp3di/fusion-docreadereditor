package com.docreader.editor.ui.viewer

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.data.xlsx.Refs
import com.docreader.editor.databinding.ItemCellBinding

class XlsxGridAdapter(private val c: XlsxEditorController) : RecyclerView.Adapter<XlsxGridAdapter.VH>() {

    class VH(val b: ItemCellBinding) : RecyclerView.ViewHolder(b.root) {
        val defaultColor = b.cell.currentTextColor
    }

    private fun sizeRow(tv: android.widget.TextView, dp: Float, hidden: Boolean = false) {
        val h = if (hidden) 0 else (44 * dp * c.scale).toInt()
        val lp = tv.layoutParams
        if (lp != null && lp.height != h) { lp.height = h; tv.layoutParams = lp }
    }

    override fun getItemCount() = (c.rowsCount() + 1) * (c.colsCount() + 1)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCellBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val tv = h.b.cell
        val dp = tv.resources.displayMetrics.density
        val cols = c.colsCount() + 1
        val gr = position / cols
        val gc = position % cols
        if (gr == 0 || gc == 0) {
            tv.text = when {
                gr == 0 && gc == 0 -> ""
                gr == 0 -> Refs.idxToCol(gc - 1)
                else -> "$gr"
            }
            tv.gravity = Gravity.CENTER
            tv.setTypeface(null, Typeface.BOLD)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * c.scale)
            sizeRow(tv, dp, gr > 0 && c.isRowHidden(gr - 1))
            tv.paintFlags = tv.paintFlags and (Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG).inv()
            tv.setTextColor(h.defaultColor)
            tv.background = GradientDrawable().apply {
                setColor(0x22888888)
                setStroke((1 * dp).toInt(), 0x55888888)
            }
            h.b.root.setOnClickListener { c.onHeaderTap(gr - 1, gc - 1) }
            h.b.root.setOnLongClickListener { false }
            return
        }
        val r = gr - 1
        val col = gc - 1
        val st = c.style(r, col)
        tv.text = c.text(r, col)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (st?.size ?: 11f) * 1.2f * c.scale)
        sizeRow(tv, dp, c.isRowHidden(r))
        val mg = c.mergeAt(r, col)
        if (mg != null && (mg[0] != r || mg[1] != col)) tv.text = ""
        val bold = st?.bold == true
        val italic = st?.italic == true
        tv.setTypeface(null, when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
        var flags = tv.paintFlags and (Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG).inv()
        if (st?.underline == true) flags = flags or Paint.UNDERLINE_TEXT_FLAG
        if (st?.strike == true) flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
        tv.paintFlags = flags
        tv.setTextColor(st?.color ?: h.defaultColor)
        tv.gravity = Gravity.CENTER_VERTICAL or when (st?.alignH) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right" -> Gravity.END
            "left" -> Gravity.START
            else -> if (c.isNumber(r, col)) Gravity.END else Gravity.START
        }
        var base = st?.fill ?: 0
        if (mg != null) base = ColorUtils.compositeColors(0x1A3B82F6, base)
        if (c.isSelected(r, col)) base = ColorUtils.compositeColors(0x443B82F6, base)
        val active = c.isActive(r, col)
        tv.background = GradientDrawable().apply {
            setColor(base)
            setStroke(
                ((if (active) 2 else 1) * dp).toInt(),
                if (active) 0xFF3B82F6.toInt() else if (st?.border == true) 0xFF444444.toInt() else 0x55888888
            )
        }
        h.b.root.setOnClickListener { c.onTap(r, col) }
        h.b.root.setOnLongClickListener { c.onLongTap(r, col); true }
    }
}
