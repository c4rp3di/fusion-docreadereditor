package com.docreader.editor.ui.viewer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.exifinterface.media.ExifInterface
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.data.docx.Block
import com.docreader.editor.data.docx.CellData
import com.docreader.editor.data.docx.DocxDoc
import com.docreader.editor.data.docx.DocxWriter
import com.docreader.editor.data.docx.DocZoom
import com.docreader.editor.data.docx.FontMap
import com.docreader.editor.data.docx.Fmt
import com.docreader.editor.data.docx.Hl
import com.docreader.editor.data.docx.ImageBlock
import com.docreader.editor.data.docx.ImageData
import com.docreader.editor.data.docx.PProps
import com.docreader.editor.data.docx.ParaBlock
import com.docreader.editor.data.docx.RowData
import com.docreader.editor.data.docx.TableBlock
import com.docreader.editor.databinding.ToolbarDocxBinding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Editor tipo Word: barra de herramientas + lista de bloques (párrafos, tablas, imágenes). */
class DocxEditorController(
    private val ctx: Context,
    private val rv: RecyclerView,
    private val tb: ToolbarDocxBinding,
    val doc: DocxDoc,
    private val onPickImage: () -> Unit,
    private val onPreview: () -> Unit
) {
    var activeEdit: ParaEditText? = null
    var activeBlock: ParaBlock? = null
    var activeTable: TableBlock? = null
    var activeCell: CellData? = null

    private var items: List<Block> = emptyList()
    private val adapter = DocxAdapter(this)
    private var lastEditTime = 0L
    private var findUnit = 0
    private var findOff = 0

    private fun commitZoom(nz: Float, r: Float, ax: Float, ay: Float, fx: Float, fy: Float) {
        val off = rv.computeVerticalScrollOffset()
        DocZoom.factor = nz
        adapter.notifyDataSetChanged()
        rv.doOnNextLayout { rv.scrollBy(0, ((off + ay) * r - fy - off).toInt()) }
    }

    private fun cycleZoom() {
        val steps = floatArrayOf(1f, 1.25f, 1.5f, 2f)
        val next = steps.firstOrNull { it > DocZoom.factor + 0.01f } ?: 1f
        commitZoom(next, next / DocZoom.factor, rv.width / 2f, rv.height / 2f, rv.width / 2f, rv.height / 2f)
    }

    init {
        DocZoom.factor = 1f
        PinchZoom(rv, 0.6f, 3f, { DocZoom.factor }, null, ::commitZoom)
        refreshItems()
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.itemAnimator = null
        rv.adapter = adapter
        bindToolbar()
        updateUndoButtons()
    }

    // ---------------------------------------------------------------- lista
    fun count() = items.size
    fun item(i: Int) = items[i]
    private fun refreshItems() { items = doc.blocks.filter { !it.hidden } }
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    fun setActive(ed: ParaEditText, para: ParaBlock?, table: TableBlock?, cell: CellData?) {
        activeEdit = ed; activeBlock = para; activeTable = table; activeCell = cell
        refreshToolbarState()
    }

    private fun clearActive() { activeEdit = null; activeBlock = null; activeTable = null; activeCell = null }

    /** Vuelca el contenido del EditText activo al modelo (tras aplicar formato). */
    private fun syncActive() {
        val e = activeEdit?.text ?: return
        activeBlock?.let { it.text = Fmt.copyText(e); it.dirty = true }
        activeCell?.let { it.text = Fmt.copyText(e); it.dirty = true; activeTable?.dirty = true }
    }

    // ---------------------------------------------------------------- deshacer
    fun shortcut(code: Int): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_Z -> { undo(); true }
        android.view.KeyEvent.KEYCODE_Y -> { redo(); true }
        android.view.KeyEvent.KEYCODE_F -> { findDialog(); true }
        android.view.KeyEvent.KEYCODE_B -> { toggle(Fmt.BOLD); true }
        android.view.KeyEvent.KEYCODE_I -> { toggle(Fmt.ITALIC); true }
        android.view.KeyEvent.KEYCODE_U -> { toggle(Fmt.UNDER); true }
        else -> false
    }

    fun dirty() = doc.modified
    fun markSaved() { doc.modified = false }

    fun checkpointNow() {
        doc.modified = true
        doc.undo.addLast(doc.snapshot())
        if (doc.undo.size > 40) doc.undo.removeFirst()
        doc.redo.clear()
        lastEditTime = SystemClock.uptimeMillis()
        updateUndoButtons()
    }

    fun onBeforeEdit() {
        doc.modified = true
        val t = SystemClock.uptimeMillis()
        if (t - lastEditTime > 1500) checkpointNow()
        lastEditTime = t
    }

    private fun undo() {
        if (doc.undo.isEmpty()) return
        doc.redo.addLast(doc.snapshot())
        restore(doc.undo.removeLast())
    }

    private fun redo() {
        if (doc.redo.isEmpty()) return
        doc.undo.addLast(doc.snapshot())
        restore(doc.redo.removeLast())
    }

    private fun restore(s: List<Block>) {
        doc.blocks.clear()
        doc.blocks.addAll(s)
        clearActive()
        refreshItems()
        adapter.notifyDataSetChanged()
        updateUndoButtons()
    }

    private fun updateUndoButtons() {
        tb.tbUndo.alpha = if (doc.undo.isEmpty()) 0.35f else 1f
        tb.tbRedo.alpha = if (doc.redo.isEmpty()) 0.35f else 1f
    }

    // ---------------------------------------------------------------- estructura
    private fun emptyPara() = ParaBlock(SpannableStringBuilder(), PProps(), null, null).also { it.dirty = true }

    private fun insertAfter(ref: Block, nb: Block) {
        doc.blocks.add(doc.blocks.indexOf(ref) + 1, nb)
        refreshItems()
        adapter.notifyItemInserted(items.indexOf(nb))
        updateMarkers()
    }

    fun splitParagraph(blk: ParaBlock, ed: ParaEditText, p: Int) {
        val e = ed.editableText ?: return
        if (p >= e.length || e[p] != '\n') return
        val tail = Fmt.copyText(e.subSequence(p + 1, e.length))
        if (blk.props.numId != null && p == 0 && tail.isEmpty()) {
            e.delete(p, e.length)           // lista vacía + Intro = salir de la lista
            blk.props.numId = null
            blk.dirty = true
            restyle(blk)
            return
        }
        e.delete(p, e.length)
        val np = blk.props.copy()
        if (np.headingLevel() >= 0) np.style = null
        np.others = np.others.filter {
            val n = it.first.substringAfter(':')
            n != "sectPr" && n != "pPrChange"
        }
        val nb = ParaBlock(tail, np, null, null).also { it.dirty = true }
        insertAfter(blk, nb)
        focusBlock(nb, 0)
    }

    fun mergeWithPrevious(b: ParaBlock): Boolean {
        if (items.indexOf(b) <= 0) return false
        rv.post { doMerge(b) }
        return true
    }

    private fun doMerge(b: ParaBlock) {
        val idx = items.indexOf(b)
        if (idx <= 0) return
        checkpointNow()
        val prev = items[idx - 1]
        if (prev is ParaBlock) {
            val junction = prev.text.length
            Fmt.appendSafe(prev.text, Fmt.copyText(b.text))
            prev.dirty = true
            doc.blocks.remove(b)
            refreshItems()
            adapter.notifyItemChanged(idx - 1)
            adapter.notifyItemRemoved(idx)
            updateMarkers()
            focusBlock(prev, junction)
        } else if (b.text.isEmpty()) {
            doc.blocks.remove(b)
            refreshItems()
            adapter.notifyItemRemoved(idx)
        }
    }

    fun deleteBlock(b: Block) {
        checkpointNow()
        doc.blocks.remove(b)
        if (doc.blocks.none { !it.hidden }) doc.blocks.add(emptyPara())
        clearActive()
        refreshItems()
        adapter.notifyDataSetChanged()
    }

    private fun deleteActive() {
        val p = activeBlock
        if (p != null) deleteBlock(p) else toast("Toca un párrafo")
    }

    fun focusBlock(b: Block, pos: Int) {
        val i = items.indexOf(b)
        if (i < 0) return
        rv.scrollToPosition(i)
        rv.postDelayed({
            val vh = rv.findViewHolderForAdapterPosition(i)
            if (vh is DocxAdapter.ParaVH) {
                val ed = vh.b.para
                ed.requestFocus()
                ed.setSelection(pos.coerceIn(0, ed.length()))
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(ed, 0)
            }
        }, 80)
    }

    private fun restyle(p: ParaBlock) {
        val vh = rv.findViewHolderForAdapterPosition(items.indexOf(p))
        if (vh is DocxAdapter.ParaVH) vh.style(p)
        updateMarkers()
        refreshToolbarState()
    }

    fun updateMarkers() {
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i))
            if (vh is DocxAdapter.ParaVH) vh.refreshMarker()
        }
    }

    // ---------------------------------------------------------------- listas
    private fun fmtFor(id: Int, ilvl: Int): String = when {
        id == DocxWriter.BULLET_ID -> "bullet"
        id == DocxWriter.DECIMAL_ABS || id >= DocxWriter.DECIMAL_BASE -> "decimal"
        else -> doc.numbering.fmt(id, ilvl)
    }

    fun marker(idx: Int): String {
        val b = items.getOrNull(idx) as? ParaBlock ?: return ""
        val id = b.props.numId ?: return ""
        val fmt = fmtFor(id, b.props.ilvl)
        if (fmt == "bullet") return if (b.props.ilvl % 2 == 0) "•" else "◦"
        var n = 1
        var i = idx - 1
        while (i >= 0) {
            val p = items[i] as? ParaBlock ?: break
            val pid = p.props.numId ?: break
            if (p.props.ilvl > b.props.ilvl) { i--; continue }
            if (p.props.ilvl < b.props.ilvl) break
            if (pid != id) break
            n++
            i--
        }
        return when (fmt) {
            "lowerLetter" -> letter(n).lowercase() + "."
            "upperLetter" -> letter(n) + "."
            "lowerRoman" -> roman(n).lowercase() + "."
            "upperRoman" -> roman(n) + "."
            else -> "$n."
        }
    }

    private fun letter(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) { x--; sb.append('A' + x % 26); x /= 26 }
        return sb.reverse().toString()
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    private fun toggleList(bullet: Boolean) {
        val p = para() ?: return
        checkpointNow()
        val want = if (bullet) "bullet" else "decimal"
        val cur = p.props.numId?.let { fmtFor(it, p.props.ilvl) }
        if (cur == want) {
            p.props.numId = null
        } else if (bullet) {
            p.props.numId = DocxWriter.BULLET_ID
        } else {
            val prev = items.getOrNull(items.indexOf(p) - 1) as? ParaBlock
            val pid = prev?.props?.numId
            p.props.numId = if (prev != null && pid != null && fmtFor(pid, prev.props.ilvl) == "decimal") pid
            else DocxWriter.DECIMAL_BASE + (doc.seq++)
        }
        p.dirty = true
        restyle(p)
    }

    // ---------------------------------------------------------------- formato de carácter
    private fun sel(): Pair<Int, Int>? {
        val ed = activeEdit ?: return null
        return min(ed.selectionStart, ed.selectionEnd) to max(ed.selectionStart, ed.selectionEnd)
    }

    private fun toggle(kind: Int) {
        val ed = activeEdit ?: return toast("Toca un texto para editarlo")
        val e = ed.editableText
        val (s, en) = sel() ?: return
        checkpointNow()
        if (s == en) {
            if (Fmt.hasAtCaret(e, s, kind)) Fmt.remove(e, s, s, kind, Fmt.EE) else Fmt.addCaret(e, s, kind, null)
        } else {
            if (Fmt.covered(e, s, en, kind)) Fmt.remove(e, s, en, kind) else Fmt.add(e, s, en, kind, null)
        }
        syncActive()
        refreshToolbarState()
    }

    private fun applyValue(kind: Int, value: Any?) {
        val ed = activeEdit ?: return toast("Toca un texto para editarlo")
        val e = ed.editableText
        val (s, en) = sel() ?: return
        checkpointNow()
        if (s == en) {
            if (value == null) Fmt.remove(e, s, s, kind, Fmt.EE) else Fmt.addCaret(e, s, kind, value)
        } else {
            if (value == null) Fmt.remove(e, s, en, kind) else Fmt.add(e, s, en, kind, value)
        }
        syncActive()
        refreshToolbarState()
    }

    private fun clearFormat() {
        val ed = activeEdit ?: return
        val e = ed.editableText
        var (s, en) = sel() ?: return
        if (s == en) { s = 0; en = e.length }
        checkpointNow()
        for (k in Fmt.ALL) Fmt.remove(e, s, en, k)
        syncActive()
        refreshToolbarState()
    }

    // ---------------------------------------------------------------- formato de párrafo
    private fun para(): ParaBlock? {
        val p = activeBlock
        if (p == null) toast("Toca un párrafo")
        return p
    }

    private fun setStyle(id: String?) {
        val p = para() ?: return
        checkpointNow()
        p.props.style = id
        p.dirty = true
        restyle(p)
    }

    private fun setAlign(j: String) {
        val p = activeBlock
        if (p != null) {
            checkpointNow(); p.props.jc = j; p.dirty = true; restyle(p)
        } else {
            val c = activeCell ?: return toast("Toca un texto para editarlo")
            checkpointNow()
            c.props.jc = j; c.dirty = true; activeTable?.dirty = true
            activeEdit?.let { DocxAdapter.applyAlign(it, j) }
        }
        refreshToolbarState()
    }

    private fun indent(delta: Int) {
        val p = para() ?: return
        checkpointNow()
        if (p.props.numId != null) p.props.ilvl = (p.props.ilvl + delta).coerceIn(0, 3)
        else { p.props.indLeft = max(0, (p.props.indLeft ?: 0) + delta * 360); p.props.indChanged = true }
        p.dirty = true
        restyle(p)
    }

    private fun setSpacing(mult: Float) {
        val p = para() ?: return
        checkpointNow()
        p.props.line = (mult * 240).toInt()
        p.props.lineChanged = true
        p.dirty = true
        restyle(p)
    }

    // ---------------------------------------------------------------- barra de herramientas
    private fun bindToolbar() {
        tb.tbPages.setOnClickListener { onPreview() }
        tb.tbZoom.setOnClickListener { cycleZoom() }
        tb.tbUndo.setOnClickListener { undo() }
        tb.tbRedo.setOnClickListener { redo() }
        tb.tbStyle.setOnClickListener { pickStyle(it) }
        tb.tbFont.setOnClickListener { pickFont() }
        tb.tbSize.setOnClickListener { pickSize() }
        tb.tbBold.setOnClickListener { toggle(Fmt.BOLD) }
        tb.tbItalic.setOnClickListener { toggle(Fmt.ITALIC) }
        tb.tbUnder.setOnClickListener { toggle(Fmt.UNDER) }
        tb.tbStrike.setOnClickListener { toggle(Fmt.STRIKE) }
        tb.tbColor.setOnClickListener { pickTextColor() }
        tb.tbHl.setOnClickListener { pickHighlight() }
        tb.tbLeft.setOnClickListener { setAlign("left") }
        tb.tbCenter.setOnClickListener { setAlign("center") }
        tb.tbRight.setOnClickListener { setAlign("right") }
        tb.tbJustify.setOnClickListener { setAlign("both") }
        tb.tbBullet.setOnClickListener { toggleList(true) }
        tb.tbNumber.setOnClickListener { toggleList(false) }
        tb.tbOutdent.setOnClickListener { indent(-1) }
        tb.tbIndent.setOnClickListener { indent(1) }
        tb.tbSpacing.setOnClickListener { pickSpacing(it) }
        tb.tbImage.setOnClickListener { onPickImage() }
        tb.tbTable.setOnClickListener { tableMenu(it) }
        tb.tbFind.setOnClickListener { findDialog() }
        tb.tbClear.setOnClickListener { clearFormat() }
        tb.tbDelPara.setOnClickListener { deleteActive() }
    }

    private fun setOn(b: Button, on: Boolean) {
        b.backgroundTintList = ColorStateList.valueOf(if (on) 0x553B82F6 else Color.TRANSPARENT)
    }

    fun refreshToolbarState() {
        val e = activeEdit?.text
        if (e == null) {
            for (b in listOf(tb.tbBold, tb.tbItalic, tb.tbUnder, tb.tbStrike, tb.tbLeft, tb.tbCenter, tb.tbRight,
                tb.tbJustify, tb.tbBullet, tb.tbNumber)) setOn(b, false)
            return
        }
        val (s, en) = sel() ?: return
        fun has(k: Int) = if (s == en) Fmt.hasAtCaret(e, s, k) else Fmt.covered(e, s, en, k)
        setOn(tb.tbBold, has(Fmt.BOLD))
        setOn(tb.tbItalic, has(Fmt.ITALIC))
        setOn(tb.tbUnder, has(Fmt.UNDER))
        setOn(tb.tbStrike, has(Fmt.STRIKE))
        val jc = activeBlock?.props?.jc ?: activeCell?.props?.jc ?: "left"
        setOn(tb.tbLeft, jc == "left")
        setOn(tb.tbCenter, jc == "center")
        setOn(tb.tbRight, jc == "right")
        setOn(tb.tbJustify, jc == "both")
        val p = activeBlock?.props
        val f = p?.numId?.let { fmtFor(it, p.ilvl) }
        setOn(tb.tbBullet, f == "bullet")
        setOn(tb.tbNumber, f != null && f != "bullet")
    }

    // ---------------------------------------------------------------- diálogos
    private fun pickStyle(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        val opts = listOf("Normal" to null, "Título" to "Title", "Título 1" to "Heading1",
            "Título 2" to "Heading2", "Título 3" to "Heading3")
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener { setStyle(opts[it.itemId].second); true }
        m.show()
    }

    private fun pickSpacing(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        val opts = listOf(1.0f, 1.15f, 1.5f, 2.0f)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, "Interlineado $o") }
        m.setOnMenuItemClickListener { setSpacing(opts[it.itemId]); true }
        m.show()
    }

    private fun pickFont() {
        val names = FontMap.fonts.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Fuente").setItems(names) { _, i -> applyValue(Fmt.FONT, names[i]) }
            .setNeutralButton("Predeterminada") { _, _ -> applyValue(Fmt.FONT, null) }.show()
    }

    private fun pickSize() {
        val sizes = intArrayOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 72)
        AlertDialog.Builder(ctx).setTitle("Tamaño").setItems(sizes.map { "$it pt" }.toTypedArray()) { _, i ->
            applyValue(Fmt.SIZE, sizes[i].toFloat())
        }.setNeutralButton("Predeterminado") { _, _ -> applyValue(Fmt.SIZE, null) }.show()
    }

    private fun pickTextColor() {
        val colors = listOf(0xFF000000, 0xFF595959, 0xFFC00000, 0xFFFF0000, 0xFFFFC000, 0xFFFFFF00,
            0xFF92D050, 0xFF00B050, 0xFF00B0F0, 0xFF0070C0, 0xFF002060, 0xFF7030A0).map { it.toInt() }
        colorDialog("Color de texto", colors) { applyValue(Fmt.COLOR, it) }
    }

    private fun pickHighlight() {
        colorDialog("Resaltado", Hl.palette.map { Hl.color(it) }) { applyValue(Fmt.HL, it) }
    }

    private fun colorDialog(title: String, colors: List<Int>, onPick: (Int?) -> Unit) {
        val grid = GridLayout(ctx).apply {
            columnCount = 6
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        lateinit var dlg: AlertDialog
        for (c in colors) {
            val v = View(ctx)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(dp(1), 0x55888888)
            }
            v.layoutParams = GridLayout.LayoutParams().apply {
                width = dp(40); height = dp(40)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            v.setOnClickListener { dlg.dismiss(); onPick(c) }
            grid.addView(v)
        }
        dlg = AlertDialog.Builder(ctx).setTitle(title).setView(grid)
            .setNeutralButton("Quitar") { _, _ -> onPick(null) }.create()
        dlg.show()
    }

    // ---------------------------------------------------------------- tablas
    private fun tableMenu(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        val opts = listOf("Insertar tabla…" to "new", "Fila arriba" to "rowabove", "Fila abajo" to "rowbelow",
            "Columna a la izquierda" to "colleft", "Columna a la derecha" to "colright",
            "Eliminar fila" to "delrow", "Eliminar columna" to "delcol", "Eliminar tabla" to "deltable")
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener { tableOp(opts[it.itemId].second); true }
        m.show()
    }

    private fun newCell(widthTwips: Int) = CellData(
        "<w:tcPr><w:tcW w:w=\"$widthTwips\" w:type=\"dxa\"/></w:tcPr>",
        SpannableStringBuilder(), PProps(), false, null, true
    )

    private fun tableOp(op: String) {
        if (op == "new") { newTableDialog(); return }
        val t = activeTable
        val cell = activeCell
        if (t == null || cell == null) return toast("Toca una celda de la tabla")
        if (op == "deltable") { deleteBlock(t); return }
        if (!t.simple) return toast("Tabla compleja: solo se puede editar el texto de las celdas")
        val ri = t.rows.indexOfFirst { r -> r.cells.any { it === cell } }
        if (ri < 0) return
        val ci = t.rows[ri].cells.indexOfFirst { it === cell }
        if ((op == "delrow" && t.rows.size <= 1) || (op == "delcol" && t.grid.size <= 1)) { deleteBlock(t); return }
        checkpointNow()
        fun newRow() = RowData("", t.grid.map { newCell(it) }.toMutableList())
        fun insertCol(at: Int) {
            val w = if (t.grid.isEmpty()) 2000 else t.grid.sum() / t.grid.size
            t.grid.add(at, w)
            t.rows.forEach { it.cells.add(at, newCell(w)) }
        }
        when (op) {
            "rowabove" -> t.rows.add(ri, newRow())
            "rowbelow" -> t.rows.add(ri + 1, newRow())
            "colleft" -> insertCol(ci)
            "colright" -> insertCol(ci + 1)
            "delrow" -> t.rows.removeAt(ri)
            "delcol" -> { t.grid.removeAt(ci); t.rows.forEach { it.cells.removeAt(ci) } }
        }
        t.dirty = true
        clearActive()
        adapter.notifyItemChanged(items.indexOf(t))
    }

    private fun newTableDialog() {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val rows = EditText(ctx).apply { hint = "Filas"; setText("3"); inputType = InputType.TYPE_CLASS_NUMBER }
        val cols = EditText(ctx).apply { hint = "Columnas"; setText("3"); inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(rows); box.addView(cols)
        AlertDialog.Builder(ctx).setTitle("Insertar tabla").setView(box)
            .setPositiveButton("Insertar") { _, _ ->
                val r = (rows.text.toString().toIntOrNull() ?: 3).coerceIn(1, 50)
                val c = (cols.text.toString().toIntOrNull() ?: 3).coerceIn(1, 12)
                insertTable(r, c)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun insertTable(r: Int, c: Int) {
        checkpointNow()
        val w = 9000 / c
        val border = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
            "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
        }
        val tblPr = "<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>$border</w:tblBorders><w:tblLook w:val=\"04A0\"/></w:tblPr>"
        val grid = MutableList(c) { w }
        val rowsL = MutableList(r) { RowData("", MutableList(c) { newCell(w) }) }
        val t = TableBlock(tblPr, grid, rowsL, true, null).also { it.dirty = true }
        val ref = activeBlock ?: activeTable ?: items.lastOrNull()
        val idx = if (ref == null) doc.blocks.size else doc.blocks.indexOf(ref) + 1
        doc.blocks.add(idx, t)
        if (doc.blocks.drop(idx + 1).firstOrNull { !it.hidden } !is ParaBlock) doc.blocks.add(idx + 1, emptyPara())
        clearActive()
        refreshItems()
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- imágenes
    fun insertImage(img: ImageData) {
        checkpointNow()
        img.id = ++doc.seq
        val ref = activeBlock ?: activeTable ?: items.lastOrNull()
        val idx = if (ref == null) doc.blocks.size else doc.blocks.indexOf(ref) + 1
        doc.blocks.add(idx, ImageBlock(img).also { it.dirty = true })
        if (doc.blocks.drop(idx + 1).firstOrNull { !it.hidden } !is ParaBlock) doc.blocks.add(idx + 1, emptyPara())
        clearActive()
        refreshItems()
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- buscar y reemplazar
    private class SearchUnit(val block: Block, val cell: CellData?, val idxInTable: Int, val text: SpannableStringBuilder)

    private fun units(): List<SearchUnit> {
        val out = ArrayList<SearchUnit>()
        for (b in items) {
            when (b) {
                is ParaBlock -> out.add(SearchUnit(b, null, -1, b.text))
                is TableBlock -> {
                    var k = 0
                    for (r in b.rows) for (c in r.cells) { if (!c.locked) out.add(SearchUnit(b, c, k, c.text)); k++ }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun findDialog() {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val f = EditText(ctx).apply { hint = "Buscar"; setSingleLine() }
        val r = EditText(ctx).apply { hint = "Reemplazar con"; setSingleLine() }
        val cs = CheckBox(ctx).apply { text = "Distinguir mayúsculas" }
        box.addView(f); box.addView(r); box.addView(cs)
        val dlg = AlertDialog.Builder(ctx).setTitle("Buscar y reemplazar").setView(box)
            .setPositiveButton("Siguiente", null)
            .setNeutralButton("Reemplazar", null)
            .setNegativeButton("Todo", null).create()
        dlg.show()
        dlg.window?.setDimAmount(0f)
        dlg.window?.setGravity(Gravity.BOTTOM)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { findNext(f.text.toString(), cs.isChecked) }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            replaceCurrent(f.text.toString(), r.text.toString(), cs.isChecked)
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            toast("${replaceAll(f.text.toString(), r.text.toString(), cs.isChecked)} reemplazos")
        }
    }

    private fun findNext(q: String, matchCase: Boolean): Boolean {
        if (q.isEmpty()) return false
        val us = units()
        if (us.isEmpty()) return false
        for (k in 0 until us.size) {
            val idx = (findUnit + k) % us.size
            val start = if (k == 0) findOff else 0
            val hit = us[idx].text.indexOf(q, start.coerceAtMost(us[idx].text.length), !matchCase)
            if (hit >= 0) {
                findUnit = idx
                findOff = hit + q.length
                select(us[idx], hit, hit + q.length)
                return true
            }
        }
        toast("No se encontró")
        findUnit = 0; findOff = 0
        return false
    }

    private fun select(u: SearchUnit, a: Int, b: Int) {
        val pos = items.indexOf(u.block)
        if (pos < 0) return
        rv.scrollToPosition(pos)
        rv.postDelayed({
            val vh = rv.findViewHolderForAdapterPosition(pos)
            val ed: ParaEditText? = when (vh) {
                is DocxAdapter.ParaVH -> vh.b.para
                is DocxAdapter.TableVH -> vh.edits.getOrNull(u.idxInTable)
                else -> null
            }
            if (ed != null && b <= ed.length()) { ed.requestFocus(); ed.setSelection(a, b) }
        }, 120)
    }

    private fun replaceCurrent(q: String, repl: String, matchCase: Boolean) {
        val ed = activeEdit
        if (ed != null && q.isNotEmpty()) {
            val s = min(ed.selectionStart, ed.selectionEnd)
            val e = max(ed.selectionStart, ed.selectionEnd)
            if (e > s && ed.text.toString().substring(s, e).equals(q, ignoreCase = !matchCase)) {
                checkpointNow()
                ed.editableText.replace(s, e, repl)
                findOff = s + repl.length
            }
        }
        findNext(q, matchCase)
    }

    private fun replaceAll(q: String, repl: String, matchCase: Boolean): Int {
        if (q.isEmpty()) return 0
        checkpointNow()
        var total = 0
        for (u in units()) {
            var idx = u.text.indexOf(q, 0, !matchCase)
            var n = 0
            while (idx >= 0) {
                u.text.replace(idx, idx + q.length, repl)
                n++
                idx = u.text.indexOf(q, idx + repl.length, !matchCase)
            }
            if (n > 0) {
                total += n
                u.block.dirty = true
                u.cell?.dirty = true
            }
        }
        if (total > 0) { clearActive(); adapter.notifyDataSetChanged() }
        return total
    }

    companion object {
        /** Lee, reduce (máx. 2400 px) y reencoda la imagen; corrige la rotación EXIF. */
        fun decodeImage(ctx: Context, uri: Uri): ImageData? {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val isPng = raw.size > 4 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte()
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2
            var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val rot = try {
                when (ExifInterface(ByteArrayInputStream(raw))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (rot != 0f) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            }
            val out = ByteArrayOutputStream()
            val ext = if (isPng) { bmp.compress(Bitmap.CompressFormat.PNG, 100, out); "png" }
            else { bmp.compress(Bitmap.CompressFormat.JPEG, 88, out); "jpeg" }
            val cx = min(bmp.width, 576) * 9525L
            val cy = cx * bmp.height / bmp.width
            return ImageData(out.toByteArray(), ext, cx, cy)
        }
    }
}
