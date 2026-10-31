package com.docreader.editor.ui.viewer

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.R
import com.docreader.editor.data.docx.Block
import com.docreader.editor.data.docx.CellData
import com.docreader.editor.data.docx.DocZoom
import com.docreader.editor.data.docx.Fmt
import com.docreader.editor.data.docx.ImageBlock
import com.docreader.editor.data.docx.ParaBlock
import com.docreader.editor.data.docx.RawBlock
import com.docreader.editor.data.docx.TableBlock
import com.docreader.editor.databinding.ItemDocxParaBinding
import com.docreader.editor.databinding.ItemDocxRawBinding
import com.docreader.editor.databinding.ItemDocxTableBinding

class DocxAdapter(private val c: DocxEditorController) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = c.count()

    override fun getItemViewType(position: Int): Int = when (c.item(position)) {
        is ParaBlock -> 0
        is TableBlock -> 1
        else -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> ParaVH(ItemDocxParaBinding.inflate(inf, parent, false), c)
            1 -> TableVH(ItemDocxTableBinding.inflate(inf, parent, false), c)
            else -> RawVH(ItemDocxRawBinding.inflate(inf, parent, false), c)
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (h) {
            is ParaVH -> h.bind(c.item(position) as ParaBlock)
            is TableVH -> h.bind(c.item(position) as TableBlock)
            is RawVH -> h.bind(c.item(position))
        }
    }

    companion object {
        fun applyAlign(ed: ParaEditText, jc: String?) {
            ed.textAlignment = when (jc) {
                "center" -> View.TEXT_ALIGNMENT_CENTER
                "right" -> View.TEXT_ALIGNMENT_VIEW_END
                else -> View.TEXT_ALIGNMENT_VIEW_START
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ed.justificationMode =
                    if (jc == "both") Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
            }
        }
    }

    class ParaVH(val b: ItemDocxParaBinding, private val c: DocxEditorController) : RecyclerView.ViewHolder(b.root) {
        private var watcher: TextWatcher? = null
        var block: ParaBlock? = null
        private var splitAt = -1
        private val defaultColor = b.para.currentTextColor

        fun bind(blk: ParaBlock) {
            block = blk
            val ed = b.para
            watcher?.let { ed.removeTextChangedListener(it) }
            ed.setText(blk.text, TextView.BufferType.EDITABLE)
            style(blk)
            val w = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, after: Int) { c.onBeforeEdit() }
                override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                    if (s != null && count > 0) {
                        val i = s.subSequence(st, st + count).indexOf('\n')
                        if (i >= 0) splitAt = st + i
                    }
                }
                override fun afterTextChanged(e: Editable?) {
                    val blk2 = block ?: return
                    if (e == null) return
                    blk2.text = Fmt.copyText(e)
                    blk2.dirty = true
                    if (splitAt >= 0) {
                        val p = splitAt
                        splitAt = -1
                        ed.post { c.splitParagraph(blk2, ed, p) }
                    }
                }
            }
            ed.addTextChangedListener(w)
            watcher = w
            ed.setOnFocusChangeListener { _, has -> if (has) block?.let { c.setActive(ed, it, null, null) } }
            ed.onBackspaceAtStart = { block?.let { c.mergeWithPrevious(it) } ?: false }
            ed.onSelection = { c.refreshToolbarState() }
            if (ed.hasFocus()) c.setActive(ed, blk, null, null)
            refreshMarker()
        }

        fun style(blk: ParaBlock) {
            val ed = b.para
            val p = blk.props
            val lvl = p.headingLevel()
            val st = c.doc.styles.resolve(p.style)
            val pt = st.sizePt ?: when (lvl) { 0 -> 19f; 1 -> 16f; 2 -> 13f; 3 -> 12f; else -> 11f }
            val sp = pt * 1.36f
            ed.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * DocZoom.factor)
            b.marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * DocZoom.factor)
            val bold = st.bold ?: (lvl >= 0)
            ed.setTypeface(null, if (bold && st.italic == true) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else if (st.italic == true) Typeface.ITALIC else Typeface.NORMAL)
            ed.setTextColor(st.color ?: defaultColor)
            applyAlign(ed, p.jc ?: st.jc)
            ed.setLineSpacing(0f, ((p.line ?: 240) / 240f).coerceIn(0.8f, 3f))
            val dp = ed.resources.displayMetrics.density
            val padDp = if (p.numId != null) 4f + p.ilvl * 24f else (p.indLeft ?: 0) / 1440f * 96f
            b.root.setPadding((padDp * dp).toInt(), 0, 0, 0)
        }

        fun refreshMarker() {
            val pos = bindingAdapterPosition
            val m = if (pos >= 0) c.marker(pos) else ""
            b.marker.text = m
            b.marker.visibility = if (m.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    class TableVH(val b: ItemDocxTableBinding, private val c: DocxEditorController) : RecyclerView.ViewHolder(b.root) {
        val edits = ArrayList<ParaEditText>()

        fun bind(blk: TableBlock) {
            val ctx = b.root.context
            val dp = ctx.resources.displayMetrics.density
            b.table.removeAllViews()
            edits.clear()
            for (row in blk.rows) {
                val tr = TableRow(ctx)
                for (cell in row.cells) {
                    val ed = ParaEditText(ctx)
                    ed.background = ContextCompat.getDrawable(ctx, R.drawable.cell_border)
                    ed.minWidth = (110 * dp * DocZoom.factor).toInt()
                    ed.setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
                    ed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * DocZoom.factor)
                    ed.gravity = Gravity.TOP or Gravity.START
                    ed.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    ed.setText(cell.text, TextView.BufferType.EDITABLE)
                    ed.isEnabled = !cell.locked
                    applyAlign(ed, cell.props.jc)
                    ed.addTextChangedListener(CellWatcher(ed, blk, cell))
                    ed.setOnFocusChangeListener { _, has -> if (has) c.setActive(ed, null, blk, cell) }
                    ed.onSelection = { c.refreshToolbarState() }
                    tr.addView(ed)
                    edits.add(ed)
                }
                b.table.addView(tr)
            }
        }

        private inner class CellWatcher(val ed: ParaEditText, val blk: TableBlock, val cell: CellData) : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, after: Int) { c.onBeforeEdit() }
            override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (e == null) return
                cell.text = Fmt.copyText(e)
                cell.dirty = true
                blk.dirty = true
            }
        }
    }

    class RawVH(val b: ItemDocxRawBinding, private val c: DocxEditorController) : RecyclerView.ViewHolder(b.root) {
        fun bind(blk: Block) {
            if (blk is ImageBlock) {
                b.rawLabel.text = "Imagen"
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = BitmapFactory.decodeByteArray(blk.image.bytes, 0, blk.image.bytes.size, o)
                b.rawImage.setImageBitmap(bmp)
                b.rawImage.visibility = View.VISIBLE
            } else {
                b.rawLabel.text = (blk as? RawBlock)?.label ?: ""
                b.rawImage.setImageDrawable(null)
                b.rawImage.visibility = View.GONE
            }
            b.rawDel.setOnClickListener { c.deleteBlock(blk) }
        }
    }
}
