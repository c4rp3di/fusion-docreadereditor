package com.docreader.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.docreader.editor.data.xlsx.Cell
import com.docreader.editor.data.xlsx.CellStyle
import com.docreader.editor.data.xlsx.Mod
import com.docreader.editor.data.xlsx.NumFmt
import com.docreader.editor.data.xlsx.Refs
import com.docreader.editor.data.xlsx.SheetData
import com.docreader.editor.data.xlsx.SheetGeometry
import com.docreader.editor.data.xlsx.SheetOps
import com.docreader.editor.data.xlsx.XClip
import com.docreader.editor.data.xlsx.XlsxDoc
import com.docreader.editor.data.xlsx.colOf
import com.docreader.editor.data.xlsx.key
import com.docreader.editor.data.xlsx.rowOf
import com.docreader.editor.databinding.FragmentViewerBinding
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Editor de hojas de cálculo con una vista propia (SheetGridView) al estilo de Google Sheets:
 * cabeceras fijas, paneles inmovilizados, columnas y filas redimensionables, selección de rango, celdas combinadas,
 * barra de fórmulas, formato, orden, filtros, copiar/pegar, hojas y deshacer.
 */
class XlsxEditorController(
    private val ctx: Context,
    private val b: FragmentViewerBinding,
    val doc: XlsxDoc,
    private val onPreview: () -> Unit
) : GridHost {
    private var sheetIdx = 0
    private val cur: SheetData get() = doc.sheets[sheetIdx]
    private var ar = 0
    private var ac = 0
    private var br = 0
    private var bc = 0
    private var extendMode = false
    private var editing = false
    private var settingText = false
    private var clip: XClip? = null
    private var findR = 0
    private var findC = 0
    private val grid get() = b.gridScroll
    private val tb get() = b.xlsxToolbar

    private class CV(val text: String, val num: Boolean)
    private val cache = HashMap<Long, CV>()
    private var cacheVer = -1
    private var cacheSheet = -1

    init {
        grid.host = this
        bindToolbar()
        bindFormulaBar()
        buildTabs()
        selectionChanged(false)
    }

    fun currentSheet(): SheetData = cur
    fun dirty() = doc.modified
    fun markSaved() { doc.modified = false }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.fxInput.windowToken, 0)
    }

    // ------------------------------------------------------------ datos para la cuadrícula
    private fun cv(r: Int, c: Int): CV {
        if (cacheVer != doc.ver || cacheSheet != sheetIdx) { cache.clear(); cacheVer = doc.ver; cacheSheet = sheetIdx }
        return cache.getOrPut(key(r, c)) {
            if (cur.cell(r, c) == null) EMPTY else CV(doc.display(cur, r, c), doc.value(cur, r, c) is Double)
        }
    }

    override fun rows() = cur.rows
    override fun cols() = cur.colsCap
    override fun colPx(c: Int) = SheetGeometry.colPx(cur, c)
    override fun rowPx(r: Int) = SheetGeometry.rowPx(cur, r)
    override fun text(r: Int, c: Int) = cv(r, c).text
    override fun style(r: Int, c: Int): CellStyle? = cur.cell(r, c)?.let { doc.styles.style(it.style) }
    override fun isNumber(r: Int, c: Int) = cv(r, c).num
    override fun isEmpty(r: Int, c: Int) = cv(r, c).text.isEmpty()
    override fun merges(): List<IntArray> = cur.merges
    override fun freezeRows() = cur.freezeR
    override fun freezeCols() = cur.freezeC
    override fun selection() = intArrayOf(min(ar, br), min(ac, bc), max(ar, br), max(ac, bc))
    override fun active() = intArrayOf(ar, ac)

    private fun rect() = selection()
    private fun lastRow() = min(cur.rows - 1, max(cur.maxRow() + 20, 29))
    private fun lastCol() = min(cur.colsCap - 1, max(cur.maxCol() + 5, 9))

    override fun onSelectCell(r: Int, c: Int) {
        if (editing) commitEdit(false)
        val mg = SheetOps.mergeAt(cur, r, c)
        val rr = mg?.get(0) ?: r
        val cc = mg?.get(1) ?: c
        if (extendMode) { br = rr; bc = cc } else { ar = rr; ac = cc; br = rr; bc = cc }
        selectionChanged(false)
    }

    override fun onSelectRange(anchorR: Int, anchorC: Int, r: Int, c: Int) {
        ar = anchorR; ac = anchorC; br = r; bc = c
        selectionChanged(false)
    }

    override fun onSelectCols(c1: Int, c2: Int) {
        if (editing) commitEdit(false)
        ar = 0; ac = c1; br = lastRow(); bc = c2
        selectionChanged(false)
    }

    override fun onSelectRows(r1: Int, r2: Int) {
        if (editing) commitEdit(false)
        ar = r1; ac = 0; br = r2; bc = lastCol()
        selectionChanged(false)
    }

    override fun onSelectAll() {
        if (editing) commitEdit(false)
        ar = 0; ac = 0; br = lastRow(); bc = lastCol()
        selectionChanged(false)
    }

    override fun onEdit() {
        b.fxInput.requestFocus()
        b.fxInput.setSelection(b.fxInput.text.length)
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(b.fxInput, 0)
    }

    override fun onResizeCol(c: Int, px: Float) {
        val q = selection()
        val lo = if (c in q[1]..q[3] && q[3] > q[1]) q[1] else c
        val hi = if (c in q[1]..q[3] && q[3] > q[1]) q[3] else c
        checkpoint()
        SheetOps.setColWidth(doc, cur, lo, hi, SheetGeometry.charsFromPx(px))
        changed()
    }

    override fun onResizeRow(r: Int, px: Float) {
        val q = selection()
        val lo = if (r in q[0]..q[2] && q[2] > q[0]) q[0] else r
        val hi = if (r in q[0]..q[2] && q[2] > q[0]) q[2] else r
        checkpoint()
        SheetOps.setRowHeight(doc, cur, lo, hi, px * 0.75f)
        changed()
    }

    override fun onAutoFitCol(c: Int) {
        var maxPx = 40f
        for (r in 0..min(cur.maxRow(), 1000)) {
            val t = cv(r, c).text
            if (t.isEmpty()) continue
            val st = style(r, c)
            val size = (st?.size ?: 11f) / 11f
            maxPx = max(maxPx, t.length * 7.4f * size * (if (st?.bold == true) 1.12f else 1f) + 14f)
        }
        checkpoint()
        SheetOps.setColWidth(doc, cur, c, c, min(80f, SheetGeometry.charsFromPx(maxPx)))
        changed()
    }

    override fun onNeedMore(rowsNear: Boolean, colsNear: Boolean) {
        var grew = false
        if (rowsNear && cur.rows < 5000) { cur.rows = min(5000, cur.rows + 30); grew = true }
        if (colsNear && cur.colsCap < 100) { cur.colsCap = min(100, cur.colsCap + 6); grew = true }
        if (grew) grid.relayout()
    }

    override fun onHeaderLongPress(isCol: Boolean, index: Int, anchor: View) {
        val q = selection()
        if (isCol) { if (index !in q[1]..q[3] || q[0] != 0) onSelectCols(index, index) }
        else { if (index !in q[0]..q[2] || q[1] != 0) onSelectRows(index, index) }
        val opts = if (isCol) listOf(
            "Insertar columna a la izquierda" to "il", "Insertar columna a la derecha" to "ir", "Eliminar columna(s)" to "dc",
            "Ocultar columna(s)" to "hidec", "Mostrar columnas ocultas" to "showall", "Ajustar ancho al contenido" to "fitc",
            "Inmovilizar hasta esta columna" to "freezec", "Ordenar A → Z" to "sortasc", "Ordenar Z → A" to "sortdesc"
        ) else listOf(
            "Insertar fila arriba" to "ia", "Insertar fila abajo" to "ib", "Eliminar fila(s)" to "df",
            "Ocultar fila(s)" to "hider", "Mostrar filas ocultas" to "showall", "Inmovilizar hasta esta fila" to "freezer"
        )
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener { structureOp(opts[it.itemId].second, index); true }
        m.show()
    }

    // ------------------------------------------------------------ selección / interfaz
    private fun selectionChanged(reveal: Boolean = false) {
        val q = selection()
        b.fxRef.text = if (q[0] == q[2] && q[1] == q[3]) Refs.addr(ar, ac) else Refs.addr(q[0], q[1]) + ":" + Refs.addr(q[2], q[3])
        if (!editing) setInput(doc.raw(cur, ar, ac))
        if (reveal) grid.scrollToCell(ar, ac)
        grid.invalidate()
        updateToolbarState()
    }

    /** Tras cambiar datos o tamaños: recalcula la cuadrícula. */
    private fun changed(reveal: Boolean = false) {
        grid.relayout()
        selectionChanged(reveal)
    }

    private fun setInput(t: String) {
        settingText = true
        b.fxInput.setText(t)
        settingText = false
    }

    // ------------------------------------------------------------ barra de fórmulas
    private fun bindFormulaBar() {
        b.fxInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { if (!settingText) editing = true }
        })
        b.fxInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { commitEdit(true); true } else false
        }
        b.fxOk.setOnClickListener { commitEdit(true) }
        b.fxCancel.setOnClickListener {
            editing = false
            setInput(doc.raw(cur, ar, ac))
            b.fxInput.clearFocus()
            hideKeyboard()
        }
    }

    private fun commitEdit(move: Boolean) {
        val v = b.fxInput.text.toString()
        val r = ar
        val c = ac
        checkpoint()
        SheetOps.setInput(doc, cur, r, c, v)
        editing = false
        if (move) {
            ar = min(r + 1, cur.rows - 1); ac = c; br = ar; bc = ac
            b.fxInput.clearFocus()
            hideKeyboard()
        }
        changed(move)
    }

    private fun insertIntoInput(t: String) {
        var s = b.fxInput.text.toString()
        if (s.isEmpty() && !t.startsWith("=")) s = "="
        val pos = b.fxInput.selectionStart.let { if (it < 0 || it > s.length) s.length else it }
        setInput(s.substring(0, pos) + t + s.substring(pos))
        editing = true
        b.fxInput.requestFocus()
        b.fxInput.setSelection(pos + t.length)
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(b.fxInput, 0)
    }

    // ------------------------------------------------------------ deshacer
    private fun checkpoint() {
        doc.modified = true
        doc.undo.addLast(doc.snapshot())
        if (doc.undo.size > 25) doc.undo.removeFirst()
        doc.redo.clear()
        updateUndo()
    }

    private fun undo() {
        if (doc.undo.isEmpty()) return
        doc.redo.addLast(doc.snapshot())
        doc.restore(doc.undo.removeLast())
        afterRestore()
    }

    private fun redo() {
        if (doc.redo.isEmpty()) return
        doc.undo.addLast(doc.snapshot())
        doc.restore(doc.redo.removeLast())
        afterRestore()
    }

    private fun afterRestore() {
        editing = false
        buildTabs()
        changed()
        updateUndo()
    }

    private fun updateUndo() {
        tb.txUndo.alpha = if (doc.undo.isEmpty()) 0.35f else 1f
        tb.txRedo.alpha = if (doc.redo.isEmpty()) 0.35f else 1f
    }

    fun shortcut(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_Z -> { undo(); true }
        KeyEvent.KEYCODE_Y -> { redo(); true }
        KeyEvent.KEYCODE_F -> { findDialog(); true }
        KeyEvent.KEYCODE_B -> { applyMod(Mod(bold = style(ar, ac)?.bold != true)); true }
        KeyEvent.KEYCODE_I -> { applyMod(Mod(italic = style(ar, ac)?.italic != true)); true }
        KeyEvent.KEYCODE_U -> { applyMod(Mod(underline = style(ar, ac)?.underline != true)); true }
        else -> false
    }

    // ------------------------------------------------------------ hojas
    private fun buildTabs() {
        b.sheetTabs.removeAllViews()
        doc.sheets.forEachIndexed { i, s ->
            val btn = Button(ctx, null, 0, com.docreader.editor.R.style.Widget_DocReader_Tool)
            btn.text = s.name
            btn.isAllCaps = false
            btn.setTypeface(null, if (i == sheetIdx) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            btn.backgroundTintList = ColorStateList.valueOf(if (i == sheetIdx) 0x553B82F6 else Color.TRANSPARENT)
            btn.setOnClickListener { switchSheet(i) }
            btn.setOnLongClickListener { renameDialog(i); true }
            b.sheetTabs.addView(btn)
        }
        val plus = Button(ctx, null, 0, com.docreader.editor.R.style.Widget_DocReader_Tool)
        plus.text = "＋"
        plus.setOnClickListener { addSheet() }
        b.sheetTabs.addView(plus)
    }

    private fun switchSheet(i: Int) {
        if (i == sheetIdx) return
        if (editing) commitEdit(false)
        sheetIdx = i
        ar = 0; ac = 0; br = 0; bc = 0
        extendMode = false
        buildTabs()
        grid.resetScroll()
        changed()
    }

    private fun addSheet() {
        if (editing) commitEdit(false)
        var n = doc.sheets.size + 1
        while (doc.sheets.any { it.name.equals("Hoja$n", true) }) n++
        var k = 1
        while (doc.sheets.any { it.path == "xl/worksheets/sheet$k.xml" } ||
            Regex("xl/worksheets/sheet$k\\.xml").containsMatchIn(String(doc.bytes, Charsets.ISO_8859_1))) k++
        doc.sheets.add(SheetData.blank("Hoja$n", "xl/worksheets/sheet$k.xml"))
        doc.modified = true
        switchSheet(doc.sheets.size - 1)
    }

    private fun renameDialog(i: Int) {
        val s = doc.sheets[i]
        val et = EditText(ctx).apply { setText(s.name); setSelection(text.length); setSingleLine() }
        val box = LinearLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(ctx).setTitle("Renombrar hoja").setView(box)
            .setPositiveButton("Aceptar") { _, _ ->
                val nn = et.text.toString().trim()
                if (nn.isEmpty() || nn.length > 31 || Regex("[\\[\\]:*?/\\\\]").containsMatchIn(nn) ||
                    doc.sheets.any { it !== s && it.name.equals(nn, true) }) {
                    toast("Nombre no válido o repetido")
                } else {
                    val old = s.name
                    checkpoint()
                    s.name = nn
                    for (o in doc.sheets) {
                        var ch = false
                        for ((k, c) in o.cells.entries.toList()) {
                            val f = c.formula ?: continue
                            val nf = Refs.renameSheet(f, old, nn)
                            if (nf != f) { o.cells[k] = Cell(c.kind, c.text, nf, c.style, c.cKind, c.cText); ch = true }
                        }
                        if (ch) o.dirty = true
                    }
                    s.dirty = true
                    doc.invalidate()
                    buildTabs()
                    changed()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ barra de herramientas
    private fun bindToolbar() {
        tb.txPrint.setOnClickListener { if (editing) commitEdit(false); onPreview() }
        tb.txZoomOut.setOnClickListener { grid.zoomBy(1f / 1.25f) }
        tb.txZoomIn.setOnClickListener { grid.zoomBy(1.25f) }
        tb.txUndo.setOnClickListener { undo() }
        tb.txRedo.setOnClickListener { redo() }
        tb.txSum.setOnClickListener { sumMenu(it) }
        tb.txFx.setOnClickListener { fxDialog() }
        tb.txBold.setOnClickListener { applyMod(Mod(bold = style(ar, ac)?.bold != true)) }
        tb.txItalic.setOnClickListener { applyMod(Mod(italic = style(ar, ac)?.italic != true)) }
        tb.txUnder.setOnClickListener { applyMod(Mod(underline = style(ar, ac)?.underline != true)) }
        tb.txFill.setOnClickListener { colorDialog("Color de relleno", true) { c -> applyMod(if (c == null) Mod(clearFill = true) else Mod(fill = c)) } }
        tb.txColor.setOnClickListener { colorDialog("Color de texto", false) { c -> applyMod(if (c == null) Mod(clearColor = true) else Mod(color = c)) } }
        tb.txSize.setOnClickListener { sizeDialog() }
        tb.txLeft.setOnClickListener { applyMod(Mod(alignH = "left")) }
        tb.txCenter.setOnClickListener { applyMod(Mod(alignH = "center")) }
        tb.txRight.setOnClickListener { applyMod(Mod(alignH = "right")) }
        tb.txFmt.setOnClickListener { fmtMenu(it) }
        tb.txDecUp.setOnClickListener { applyMod(Mod(numFmt = NumFmt.adjustDecimals(style(ar, ac)?.numFmt ?: "General", 1))) }
        tb.txDecDown.setOnClickListener { applyMod(Mod(numFmt = NumFmt.adjustDecimals(style(ar, ac)?.numFmt ?: "General", -1))) }
        tb.txBorder.setOnClickListener { applyMod(Mod(border = style(ar, ac)?.border != true)) }
        tb.txRange.setOnClickListener {
            extendMode = !extendMode
            if (!extendMode) { br = ar; bc = ac; selectionChanged() }
            updateToolbarState()
            toast(if (extendMode) "Toca la celda final para ampliar la selección" else "Selección normal")
        }
        tb.txCopy.setOnClickListener { copy(false) }
        tb.txCut.setOnClickListener { copy(true) }
        tb.txPaste.setOnClickListener { paste() }
        tb.txClear.setOnClickListener {
            val q = rect(); checkpoint(); SheetOps.clear(doc, cur, q[0], q[1], q[2], q[3]); changed()
        }
        tb.txRows.setOnClickListener { rowsMenu(it) }
        tb.txFreeze.setOnClickListener { freezeMenu(it) }
        tb.txSort.setOnClickListener { sortMenu(it) }
        tb.txFilter.setOnClickListener { filterMenu(it) }
        tb.txFind.setOnClickListener { findDialog() }
    }

    private fun setOn(btn: Button, on: Boolean) {
        btn.backgroundTintList = ColorStateList.valueOf(if (on) 0x553B82F6 else Color.TRANSPARENT)
    }

    private fun updateToolbarState() {
        val st = style(ar, ac)
        setOn(tb.txBold, st?.bold == true)
        setOn(tb.txItalic, st?.italic == true)
        setOn(tb.txUnder, st?.underline == true)
        setOn(tb.txBorder, st?.border == true)
        setOn(tb.txLeft, st?.alignH == "left")
        setOn(tb.txCenter, st?.alignH == "center")
        setOn(tb.txRight, st?.alignH == "right")
        setOn(tb.txRange, extendMode)
        updateUndo()
    }

    private fun applyMod(m: Mod) {
        if (editing) commitEdit(false)
        val q = rect()
        checkpoint()
        SheetOps.applyStyle(doc, cur, q[0], q[1], q[2], q[3], m)
        changed()
    }

    // ------------------------------------------------------------ menús y diálogos
    private fun sumMenu(anchor: View) {
        val opts = listOf("Suma" to "SUM", "Promedio" to "AVERAGE", "Contar" to "COUNT", "Máximo" to "MAX", "Mínimo" to "MIN")
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            if (editing) commitEdit(false)
            val q = rect()
            checkpoint()
            val (r, c) = SheetOps.autoFunction(doc, cur, opts[it.itemId].second, q[0], q[1], q[2], q[3])
            ar = r; ac = c; br = r; bc = c
            changed(true)
            true
        }
        m.show()
    }

    private fun fxDialog() {
        val fns = arrayOf("SUM(", "AVERAGE(", "MIN(", "MAX(", "COUNT(", "COUNTA(", "IF(", "IFERROR(", "IFS(", "AND(", "OR(",
            "ROUND(", "ABS(", "SQRT(", "POWER(", "MOD(", "SUMIF(", "SUMIFS(", "COUNTIF(", "COUNTIFS(", "AVERAGEIF(", "VLOOKUP(",
            "XLOOKUP(", "INDEX(", "MATCH(", "CONCAT(", "TEXTJOIN(", "LEFT(", "RIGHT(", "MID(", "LEN(", "UPPER(", "LOWER(", "TRIM(",
            "TEXT(", "TODAY(", "NOW(", "DATE(", "YEAR(", "MONTH(", "DAY(", "LARGE(", "SMALL(")
        AlertDialog.Builder(ctx).setTitle("Insertar función").setItems(fns) { _, i -> insertIntoInput(fns[i]) }.show()
    }

    private fun fmtMenu(anchor: View) {
        val cur2 = try { Currency.getInstance(Locale.getDefault()).symbol } catch (e: Exception) { "$" }
        val opts = listOf(
            "General" to "General", "Número (0,00)" to "0.00", "Miles (#.##0)" to "#,##0",
            "Moneda" to "#,##0.00\\ \"$cur2\"", "Porcentaje" to "0.00%", "Fecha" to "dd/mm/yyyy", "Hora" to "h:mm:ss",
            "Fecha y hora" to "dd/mm/yyyy\\ h:mm", "Texto" to "@"
        )
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener { applyMod(Mod(numFmt = opts[it.itemId].second)); true }
        m.show()
    }

    private fun sizeDialog() {
        val sizes = intArrayOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48)
        AlertDialog.Builder(ctx).setTitle("Tamaño de fuente")
            .setItems(sizes.map { "$it pt" }.toTypedArray()) { _, i -> applyMod(Mod(size = sizes[i].toFloat())) }.show()
    }

    private fun colorDialog(title: String, allowNone: Boolean, onPick: (Int?) -> Unit) {
        val colors = listOf(0xFF000000, 0xFF595959, 0xFFFFFFFF, 0xFFFFFF00, 0xFFFFC000, 0xFFFF0000, 0xFFC00000,
            0xFF92D050, 0xFF00B050, 0xFF00B0F0, 0xFF0070C0, 0xFF002060, 0xFF7030A0, 0xFFFFD9D9, 0xFFFFF2CC,
            0xFFE2EFDA, 0xFFDDEBF7, 0xFFEDEDED).map { it.toInt() }
        val grid = GridLayout(ctx).apply { columnCount = 6; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var dlg: AlertDialog
        for (c in colors) {
            val v = View(ctx)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), 0x55888888) }
            v.layoutParams = GridLayout.LayoutParams().apply { width = dp(40); height = dp(40); setMargins(dp(6), dp(6), dp(6), dp(6)) }
            v.setOnClickListener { dlg.dismiss(); onPick(c) }
            grid.addView(v)
        }
        val bld = AlertDialog.Builder(ctx).setTitle(title).setView(grid)
        if (allowNone) bld.setNeutralButton("Sin color") { _, _ -> onPick(null) }
        else bld.setNeutralButton("Automático") { _, _ -> onPick(null) }
        dlg = bld.create()
        dlg.show()
    }

    private fun copy(cut: Boolean) {
        if (editing) commitEdit(false)
        val q = rect()
        val c = SheetOps.copy(doc, cur, q[0], q[1], q[2], q[3], cut)
        clip = c
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("celdas", c.tsv))
        toast(if (cut) "Cortado" else "Copiado")
    }

    private fun paste() {
        if (editing) commitEdit(false)
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val sys = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        val q = rect()
        val c = clip
        checkpoint()
        if (c != null && (sys == null || sys.isEmpty() || sys == c.tsv)) {
            SheetOps.paste(doc, cur, c, q[0], q[1])
            if (c.cut) clip = null
        } else if (!sys.isNullOrEmpty()) {
            SheetOps.pasteText(doc, cur, sys, q[0], q[1])
        } else {
            doc.undo.removeLast(); updateUndo()
            return toast("Nada que pegar")
        }
        changed()
    }

    // ------------------------------------------------------------ estructura: filas, columnas, ocultar, combinar, rellenar
    private fun structureOp(op: String, index: Int = -1) {
        if (editing) commitEdit(false)
        val q = selection()
        val nr = q[2] - q[0] + 1
        val nc = q[3] - q[1] + 1
        checkpoint()
        when (op) {
            "ia" -> SheetOps.shiftRows(doc, cur, q[0], nr)
            "ib" -> SheetOps.shiftRows(doc, cur, q[2] + 1, nr)
            "il" -> SheetOps.shiftCols(doc, cur, q[1], nc)
            "ir" -> SheetOps.shiftCols(doc, cur, q[3] + 1, nc)
            "df" -> SheetOps.shiftRows(doc, cur, q[0], -nr)
            "dc" -> SheetOps.shiftCols(doc, cur, q[1], -nc)
            "merge" -> SheetOps.merge(doc, cur, q[0], q[1], q[2], q[3])
            "unmerge" -> SheetOps.unmerge(doc, cur, q[0], q[1], q[2], q[3])
            "hider" -> SheetOps.hideRows(doc, cur, q[0], q[2])
            "hidec" -> SheetOps.setColHidden(doc, cur, q[1], q[3], true)
            "showall" -> { SheetOps.showAllRows(doc, cur); SheetOps.showAllCols(doc, cur) }
            "fitc" -> { for (c in q[1]..q[3]) autoFitNoCheckpoint(c) }
            "fillDown" -> SheetOps.fill(doc, cur, q[0], q[1], q[2], q[3], true)
            "fillRight" -> SheetOps.fill(doc, cur, q[0], q[1], q[2], q[3], false)
            "freezec" -> freezeTo(cur.freezeR, (if (index >= 0) index else q[3]) + 1)
            "freezer" -> freezeTo((if (index >= 0) index else q[2]) + 1, cur.freezeC)
            "sortasc", "sortdesc" -> {
                val col0 = index.coerceAtLeast(0)
                val row0 = (0..cur.maxRow()).firstOrNull { !isEmpty(it, col0) } ?: 0
                val reg = SheetOps.currentRegion(cur, row0, col0)
                SheetOps.sort(doc, cur, reg[0] + 1, reg[1], reg[2], reg[3], index.coerceAtLeast(reg[1]), op == "sortasc")
            }
        }
        if (op.startsWith("d") || op == "ia" || op == "il") { br = ar; bc = ac }
        changed()
    }

    private fun autoFitNoCheckpoint(c: Int) {
        var maxPx = 40f
        for (r in 0..min(cur.maxRow(), 1000)) {
            val t = cv(r, c).text
            if (t.isEmpty()) continue
            val st = style(r, c)
            maxPx = max(maxPx, t.length * 7.4f * ((st?.size ?: 11f) / 11f) * (if (st?.bold == true) 1.12f else 1f) + 14f)
        }
        SheetOps.setColWidth(doc, cur, c, c, min(80f, SheetGeometry.charsFromPx(maxPx)))
    }

    private fun freezeTo(r: Int, c: Int) {
        cur.freezeR = r.coerceAtLeast(0)
        cur.freezeC = c.coerceAtLeast(0)
        cur.freezeDirty = true
        cur.dirty = true
    }

    private fun rowsMenu(anchor: View) {
        val opts = listOf(
            "Insertar fila(s) arriba" to "ia", "Insertar fila(s) abajo" to "ib",
            "Insertar columna(s) a la izquierda" to "il", "Insertar columna(s) a la derecha" to "ir",
            "Eliminar fila(s)" to "df", "Eliminar columna(s)" to "dc",
            "Combinar celdas" to "merge", "Separar celdas" to "unmerge",
            "Ocultar fila(s)" to "hider", "Ocultar columna(s)" to "hidec", "Mostrar filas y columnas ocultas" to "showall",
            "Ajustar ancho al contenido" to "fitc",
            "Rellenar hacia abajo" to "fillDown", "Rellenar hacia la derecha" to "fillRight"
        )
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener { structureOp(opts[it.itemId].second); true }
        m.show()
    }

    private fun freezeMenu(anchor: View) {
        val opts = listOf(
            "Inmovilizar 1 fila" to "r1", "Inmovilizar 1 columna" to "c1",
            "Inmovilizar hasta la fila ${ar + 1}" to "rc", "Inmovilizar hasta la columna ${Refs.idxToCol(ac)}" to "cc",
            "Quitar inmovilización" to "none"
        )
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            when (opts[it.itemId].second) {
                "r1" -> freezeTo(1, cur.freezeC)
                "c1" -> freezeTo(cur.freezeR, 1)
                "rc" -> freezeTo(ar + 1, cur.freezeC)
                "cc" -> freezeTo(cur.freezeR, ac + 1)
                else -> freezeTo(0, 0)
            }
            doc.modified = true
            changed()
            true
        }
        m.show()
    }

    private fun sortMenu(anchor: View) {
        val opts = listOf("A → Z (con encabezado)" to Pair(true, true), "Z → A (con encabezado)" to Pair(false, true),
            "A → Z (sin encabezado)" to Pair(true, false), "Z → A (sin encabezado)" to Pair(false, false))
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            if (editing) commitEdit(false)
            val (asc, header) = opts[it.itemId].second
            val q = rect()
            val reg = if (q[0] == q[2] && q[1] == q[3]) SheetOps.currentRegion(cur, ar, ac) else q
            var keyCol = ac
            if (keyCol !in reg[1]..reg[3]) keyCol = reg[1]
            val r1 = if (header) reg[0] + 1 else reg[0]
            checkpoint()
            SheetOps.sort(doc, cur, r1, reg[1], reg[2], reg[3], keyCol, asc)
            changed()
            true
        }
        m.show()
    }

    // ------------------------------------------------------------ filtrar
    private fun filterMenu(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        m.menu.add(0, 0, 0, "Filtrar por valores de esta columna…")
        m.menu.add(0, 1, 1, "Mostrar todas las filas")
        m.setOnMenuItemClickListener {
            if (editing) commitEdit(false)
            if (it.itemId == 1) { checkpoint(); SheetOps.showAllRows(doc, cur); changed() }
            else filterDialog()
            true
        }
        m.show()
    }

    private fun filterDialog() {
        val q = rect()
        val reg = if (q[0] == q[2] && q[1] == q[3]) SheetOps.currentRegion(cur, ar, ac) else q
        val r1 = reg[0] + 1
        val r2 = reg[2]
        if (r2 < r1) { toast("No hay filas de datos que filtrar"); return }
        val col = ac
        val values = (r1..r2).map { doc.display(cur, it, col) }.distinct().sorted().take(300)
        val checked = BooleanArray(values.size) { i ->
            (r1..r2).any { r -> doc.display(cur, r, col) == values[i] && !SheetOps.isRowHidden(cur, r) }
        }
        AlertDialog.Builder(ctx).setTitle("Mostrar filas con…")
            .setMultiChoiceItems(values.map { it.ifEmpty { "(vacío)" } }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Aplicar") { _, _ ->
                val vis = values.filterIndexed { i, _ -> checked[i] }.toSet()
                checkpoint()
                SheetOps.filterRows(doc, cur, r1, r2, col, vis)
                changed()
            }
            .setNeutralButton("Mostrar todas") { _, _ -> checkpoint(); SheetOps.showAllRows(doc, cur); changed() }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ buscar y reemplazar
    private fun findDialog() {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val f = EditText(ctx).apply { hint = "Buscar"; setSingleLine() }
        val r = EditText(ctx).apply { hint = "Reemplazar con"; setSingleLine() }
        val cs = CheckBox(ctx).apply { text = "Distinguir mayúsculas" }
        box.addView(f); box.addView(r); box.addView(cs)
        val dlg = AlertDialog.Builder(ctx).setTitle("Buscar y reemplazar").setView(box)
            .setPositiveButton("Siguiente", null).setNeutralButton("Reemplazar", null).setNegativeButton("Todo", null).create()
        dlg.show()
        dlg.window?.setDimAmount(0f)
        dlg.window?.setGravity(Gravity.BOTTOM)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { findNext(f.text.toString(), cs.isChecked) }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { replaceCurrent(f.text.toString(), r.text.toString(), cs.isChecked) }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            toast("${replaceAll(f.text.toString(), r.text.toString(), cs.isChecked)} reemplazos")
        }
    }

    private fun findNext(q: String, matchCase: Boolean): Boolean {
        if (q.isEmpty()) return false
        val keys = cur.cells.keys.sortedWith(compareBy({ rowOf(it) }, { colOf(it) }))
        if (keys.isEmpty()) return false
        val start = keys.indexOfFirst {
            val r = rowOf(it); val c = colOf(it)
            r > findR || (r == findR && c > findC)
        }.let { if (it < 0) 0 else it }
        for (k in 0 until keys.size) {
            val kk = keys[(start + k) % keys.size]
            val r = rowOf(kk); val c = colOf(kk)
            if (doc.display(cur, r, c).contains(q, ignoreCase = !matchCase)) {
                findR = r; findC = c
                ar = r; ac = c; br = r; bc = c
                selectionChanged(true)
                return true
            }
        }
        toast("No se encontró")
        findR = 0; findC = -1
        return false
    }

    private fun replaceCurrent(q: String, repl: String, matchCase: Boolean) {
        val c = cur.cell(ar, ac)
        if (q.isNotEmpty() && c != null && c.kind == Cell.STR && c.formula == null && c.text.contains(q, ignoreCase = !matchCase)) {
            checkpoint()
            SheetOps.setInput(doc, cur, ar, ac, "'" + c.text.replace(q, repl, ignoreCase = !matchCase))
            changed()
        }
        findNext(q, matchCase)
    }

    private fun replaceAll(q: String, repl: String, matchCase: Boolean): Int {
        if (q.isEmpty()) return 0
        checkpoint()
        var n = 0
        for ((k, c) in cur.cells.entries.toList()) {
            if (c.kind == Cell.STR && c.formula == null && c.text.contains(q, ignoreCase = !matchCase)) {
                cur.cells[k] = Cell(Cell.STR, c.text.replace(q, repl, ignoreCase = !matchCase), null, c.style)
                n++
            }
        }
        if (n > 0) { cur.dirty = true; doc.invalidate(); changed() }
        return n
    }

    companion object { private val EMPTY = CV("", false) }
}
