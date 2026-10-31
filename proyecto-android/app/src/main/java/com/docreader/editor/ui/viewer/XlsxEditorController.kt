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
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.docreader.editor.data.xlsx.Cell
import com.docreader.editor.data.xlsx.CellStyle
import com.docreader.editor.data.xlsx.Mod
import com.docreader.editor.data.xlsx.NumFmt
import com.docreader.editor.data.xlsx.Refs
import com.docreader.editor.data.xlsx.SheetData
import com.docreader.editor.data.xlsx.SheetOps
import com.docreader.editor.data.xlsx.XClip
import com.docreader.editor.data.xlsx.XlsxDoc
import com.docreader.editor.databinding.FragmentViewerBinding
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Editor tipo Excel: barra de fórmulas, formato de celda, filas/columnas, ordenar, copiar/pegar, hojas. */
class XlsxEditorController(
    private val ctx: Context,
    private val b: FragmentViewerBinding,
    val doc: XlsxDoc,
    private val onPreview: () -> Unit
) {
    private var sheetIdx = 0
    private val cur: SheetData get() = doc.sheets[sheetIdx]
    private var ar = 0
    private var ac = 0
    private var br = 0
    private var bc = 0
    private var rangeMode = false
    private var editing = false
    private var settingText = false
    private var clip: XClip? = null
    private var adapter: XlsxGridAdapter? = null
    private var spanCols = -1
    var scale = 1f
        private set
    private var findR = 0
    private var findC = 0
    private val tb get() = b.xlsxToolbar

    init {
        PinchZoom(b.grid, 0.5f, 3f, { scale }, null, ::commitZoom)
        bindToolbar()
        bindFormulaBar()
        buildTabs()
        rebuildGrid()
        selectionChanged()
    }

    fun currentSheet(): SheetData = cur

    // ------------------------------------------------------------ acceso para el adaptador
    fun rowsCount() = cur.rows
    fun colsCount() = cur.colsCap
    fun text(r: Int, c: Int) = doc.display(cur, r, c)
    fun style(r: Int, c: Int): CellStyle? = cur.cell(r, c)?.let { doc.styles.style(it.style) }
    fun isNumber(r: Int, c: Int) = cell(r, c)?.let { doc.value(cur, r, c) is Double } == true
    private fun cell(r: Int, c: Int): Cell? = cur.cell(r, c)
    fun mergeAt(r: Int, c: Int): IntArray? = SheetOps.mergeAt(cur, r, c)
    fun isRowHidden(r: Int) = SheetOps.isRowHidden(cur, r)
    fun isActive(r: Int, c: Int) = r == ar && c == ac
    fun isSelected(r: Int, c: Int): Boolean {
        val q = rect()
        return r in q[0]..q[2] && c in q[1]..q[3] && (q[0] != q[2] || q[1] != q[3])
    }

    private fun rect() = intArrayOf(min(ar, br), min(ac, bc), max(ar, br), max(ac, bc))
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.fxInput.windowToken, 0)
    }

    // ------------------------------------------------------------ cuadrícula
    private fun rebuildGrid() {
        val cols = cur.colsCap + 1
        spanCols = cols
        b.grid.layoutManager = GridLayoutManager(ctx, cols)
        b.grid.layoutParams = b.grid.layoutParams.apply { width = (cols * dp(100) * scale).toInt() }
        b.grid.itemAnimator = null
        adapter = XlsxGridAdapter(this)
        b.grid.adapter = adapter
    }

    private fun commitZoom(nz: Float, r: Float, ax: Float, ay: Float, fx: Float, fy: Float) {
        val sx = b.gridScroll.scrollX
        val off = b.grid.computeVerticalScrollOffset()
        scale = nz
        b.grid.layoutParams = b.grid.layoutParams.apply { width = ((cur.colsCap + 1) * dp(100) * scale).toInt() }
        adapter?.notifyDataSetChanged()
        b.gridScroll.doOnNextLayout { b.gridScroll.scrollTo(max(0, (ax * r - (fx - sx)).toInt()), 0) }
        b.grid.doOnNextLayout { b.grid.scrollBy(0, ((off + ay) * r - fy - off).toInt()) }
    }

    private fun zoomButton(f: Float) {
        val nz = (scale * f).coerceIn(0.5f, 3f)
        if (kotlin.math.abs(nz - scale) < 0.01f) return
        val cx = b.gridScroll.scrollX + b.gridScroll.width / 2f
        val cy = b.grid.height / 2f
        commitZoom(nz, nz / scale, cx, cy, cx, cy)
    }

    private fun refresh() {
        if (cur.colsCap + 1 != spanCols) rebuildGrid() else adapter?.notifyDataSetChanged()
    }

    private fun selectionChanged() {
        val q = rect()
        b.fxRef.text = if (q[0] == q[2] && q[1] == q[3]) Refs.addr(ar, ac)
        else Refs.addr(q[0], q[1]) + ":" + Refs.addr(q[2], q[3])
        if (!editing) setInput(doc.raw(cur, ar, ac))
        refresh()
        updateToolbarState()
    }

    private fun setInput(t: String) {
        settingText = true
        b.fxInput.setText(t)
        settingText = false
    }

    fun onTap(r: Int, c: Int) {
        if (editing) commitEdit(false)
        val mg = SheetOps.mergeAt(cur, r, c)
        val rr = mg?.get(0) ?: r
        val cc = mg?.get(1) ?: c
        if (rangeMode) { br = rr; bc = cc } else { ar = rr; ac = cc; br = rr; bc = cc }
        selectionChanged()
        if (!rangeMode && r == ar && c == ac) Unit
    }

    fun onLongTap(r: Int, c: Int) {
        if (editing) commitEdit(false)
        ar = r; ac = c; br = r; bc = c
        rangeMode = true
        updateToolbarState()
        selectionChanged()
        toast("Modo rango: toca la celda final")
    }

    fun onHeaderTap(r: Int, c: Int) {
        if (editing) commitEdit(false)
        val lastRow = min(cur.rows - 1, max(cur.maxRow() + 20, 29))
        val lastCol = min(cur.colsCap - 1, max(cur.maxCol() + 5, 9))
        if (r >= 0 && c < 0) { // cabecera de fila
            if (rangeMode) { br = r; bc = lastCol } else { ar = r; ac = 0; br = r; bc = lastCol }
        } else if (c >= 0 && r < 0) { // cabecera de columna
            if (rangeMode) { br = lastRow; bc = c } else { ar = 0; ac = c; br = lastRow; bc = c }
        } else { ar = 0; ac = 0; br = lastRow; bc = lastCol }
        selectionChanged()
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
        selectionChanged()
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
    fun shortcut(code: Int): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_Z -> { undo(); true }
        android.view.KeyEvent.KEYCODE_Y -> { redo(); true }
        android.view.KeyEvent.KEYCODE_F -> { findDialog(); true }
        android.view.KeyEvent.KEYCODE_B -> { applyMod(Mod(bold = style(ar, ac)?.bold != true)); true }
        android.view.KeyEvent.KEYCODE_I -> { applyMod(Mod(italic = style(ar, ac)?.italic != true)); true }
        android.view.KeyEvent.KEYCODE_U -> { applyMod(Mod(underline = style(ar, ac)?.underline != true)); true }
        else -> false
    }

    fun dirty() = doc.modified
    fun markSaved() { doc.modified = false }

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
        selectionChanged()
        updateUndo()
    }

    private fun updateUndo() {
        tb.txUndo.alpha = if (doc.undo.isEmpty()) 0.35f else 1f
        tb.txRedo.alpha = if (doc.redo.isEmpty()) 0.35f else 1f
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
        rangeMode = false
        buildTabs()
        rebuildGrid()
        selectionChanged()
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
                    selectionChanged()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ barra de herramientas
    private fun bindToolbar() {
        tb.txPrint.setOnClickListener { if (editing) commitEdit(false); onPreview() }
        tb.txZoomOut.setOnClickListener { zoomButton(1f / 1.25f) }
        tb.txZoomIn.setOnClickListener { zoomButton(1.25f) }
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
            rangeMode = !rangeMode
            if (!rangeMode) { br = ar; bc = ac; selectionChanged() }
            updateToolbarState()
            toast(if (rangeMode) "Modo rango activado" else "Modo rango desactivado")
        }
        tb.txCopy.setOnClickListener { copy(false) }
        tb.txCut.setOnClickListener { copy(true) }
        tb.txPaste.setOnClickListener { paste() }
        tb.txClear.setOnClickListener {
            val q = rect(); checkpoint(); SheetOps.clear(doc, cur, q[0], q[1], q[2], q[3]); selectionChanged()
        }
        tb.txRows.setOnClickListener { rowsMenu(it) }
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
        setOn(tb.txRange, rangeMode)
        updateUndo()
    }

    private fun applyMod(m: Mod) {
        if (editing) commitEdit(false)
        val q = rect()
        checkpoint()
        SheetOps.applyStyle(doc, cur, q[0], q[1], q[2], q[3], m)
        selectionChanged()
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
            selectionChanged()
            true
        }
        m.show()
    }

    private fun fxDialog() {
        val fns = arrayOf("SUM(", "AVERAGE(", "MIN(", "MAX(", "COUNT(", "COUNTA(", "IF(", "IFERROR(", "AND(", "OR(",
            "ROUND(", "ABS(", "SQRT(", "POWER(", "MOD(", "SUMIF(", "COUNTIF(", "AVERAGEIF(", "VLOOKUP(", "INDEX(",
            "MATCH(", "CONCAT(", "LEFT(", "RIGHT(", "MID(", "LEN(", "UPPER(", "LOWER(", "TRIM(", "TEXT(",
            "TODAY(", "NOW(", "DATE(", "YEAR(", "MONTH(", "DAY(")
        AlertDialog.Builder(ctx).setTitle("Insertar función").setItems(fns) { _, i -> insertIntoInput(fns[i]) }.show()
    }

    private fun fmtMenu(anchor: View) {
        val cur2 = try { Currency.getInstance(Locale.getDefault()).symbol } catch (e: Exception) { "$" }
        val opts = listOf(
            "General" to "General",
            "Número (0,00)" to "0.00",
            "Miles (#.##0)" to "#,##0",
            "Moneda" to "#,##0.00\\ \"$cur2\"",
            "Porcentaje" to "0.00%",
            "Fecha" to "dd/mm/yyyy",
            "Hora" to "h:mm:ss",
            "Fecha y hora" to "dd/mm/yyyy\\ h:mm",
            "Texto" to "@"
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
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("celdas", c.tsv))
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
        selectionChanged()
    }

    private fun rowsMenu(anchor: View) {
        val q = rect()
        val nr = q[2] - q[0] + 1
        val nc = q[3] - q[1] + 1
        val opts = listOf("Insertar fila(s) arriba" to "ia", "Insertar fila(s) abajo" to "ib",
            "Insertar columna(s) a la izquierda" to "il", "Insertar columna(s) a la derecha" to "ir",
            "Eliminar fila(s)" to "df", "Eliminar columna(s)" to "dc",
            "Combinar celdas" to "merge", "Separar celdas" to "unmerge")
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            if (editing) commitEdit(false)
            checkpoint()
            when (opts[it.itemId].second) {
                "ia" -> SheetOps.shiftRows(doc, cur, q[0], nr)
                "ib" -> SheetOps.shiftRows(doc, cur, q[2] + 1, nr)
                "il" -> SheetOps.shiftCols(doc, cur, q[1], nc)
                "ir" -> SheetOps.shiftCols(doc, cur, q[3] + 1, nc)
                "df" -> SheetOps.shiftRows(doc, cur, q[0], -nr)
                "dc" -> SheetOps.shiftCols(doc, cur, q[1], -nc)
                "merge" -> SheetOps.merge(doc, cur, q[0], q[1], q[2], q[3])
                "unmerge" -> SheetOps.unmerge(doc, cur, q[0], q[1], q[2], q[3])
            }
            br = ar; bc = ac
            selectionChanged()
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
            selectionChanged()
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
            if (it.itemId == 1) { checkpoint(); SheetOps.showAllRows(doc, cur); selectionChanged() }
            else filterDialog()
            true
        }
        m.show()
    }

    private fun filterDialog() {
        val q = rect()
        val reg = if (q[0] == q[2] && q[1] == q[3]) SheetOps.currentRegion(cur, ar, ac) else q
        val r1 = reg[0] + 1          // la primera fila es el encabezado
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
                selectionChanged()
            }
            .setNeutralButton("Mostrar todas") { _, _ -> checkpoint(); SheetOps.showAllRows(doc, cur); selectionChanged() }
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
        val keys = cur.cells.keys.sortedWith(compareBy({ com.docreader.editor.data.xlsx.rowOf(it) }, { com.docreader.editor.data.xlsx.colOf(it) }))
        if (keys.isEmpty()) return false
        val start = keys.indexOfFirst {
            val r = com.docreader.editor.data.xlsx.rowOf(it); val c = com.docreader.editor.data.xlsx.colOf(it)
            r > findR || (r == findR && c > findC)
        }.let { if (it < 0) 0 else it }
        for (k in 0 until keys.size) {
            val key = keys[(start + k) % keys.size]
            val r = com.docreader.editor.data.xlsx.rowOf(key); val c = com.docreader.editor.data.xlsx.colOf(key)
            if (doc.display(cur, r, c).contains(q, ignoreCase = !matchCase)) {
                findR = r; findC = c
                ar = r; ac = c; br = r; bc = c
                selectionChanged()
                b.grid.scrollToPosition((r + 1) * (cur.colsCap + 1) + c + 1)
                return true
            }
        }
        toast("No se encontró")
        findR = 0; findC = -1
        return false
    }

    private fun replaceCurrent(q: String, repl: String, matchCase: Boolean) {
        val c = cell(ar, ac)
        if (q.isNotEmpty() && c != null && c.kind == Cell.STR && c.formula == null && c.text.contains(q, ignoreCase = !matchCase)) {
            checkpoint()
            SheetOps.setInput(doc, cur, ar, ac, "'" + c.text.replace(q, repl, ignoreCase = !matchCase))
            selectionChanged()
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
        if (n > 0) { cur.dirty = true; doc.invalidate(); selectionChanged() }
        return n
    }
}
