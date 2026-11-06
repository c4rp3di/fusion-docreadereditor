package com.docreader.editor.data.xlsx

import kotlin.math.abs

class XClip(
    val cells: Map<Long, Cell>,      // posición relativa (fila, col) respecto al origen
    val rows: Int,
    val cols: Int,
    val tsv: String,
    val cut: Boolean,
    val srcSheet: SheetData,
    val sr: Int,
    val sc: Int
)

object SheetOps {
    private val numRe = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    private fun touch(doc: XlsxDoc, sh: SheetData) { sh.dirty = true; doc.invalidate() }

    private fun parseNumber(t0: String): Double? {
        var t = t0.trim()
        if (t.count { it == ',' } == 1 && !t.contains('.')) t = t.replace(',', '.')
        return if (numRe.matches(t)) t.toDoubleOrNull()?.takeIf { it.isFinite() } else null
    }

    fun setInput(doc: XlsxDoc, sh: SheetData, r: Int, c: Int, input: String) {
        val old = sh.cell(r, c)
        var style = old?.style ?: 0
        val t = input
        val cell: Cell? = when {
            t.isEmpty() -> if (style != 0) Cell(Cell.EMPTY, "", null, style) else null
            t.startsWith("=") && t.length > 1 -> Cell(Cell.NUM, "", t.substring(1), style)
            t.startsWith("'") -> Cell(Cell.STR, t.substring(1), null, style)
            t.equals("TRUE", true) -> Cell(Cell.BOOL, "1", null, style)
            t.equals("FALSE", true) -> Cell(Cell.BOOL, "0", null, style)
            else -> {
                val pct = if (t.trim().endsWith("%")) parseNumber(t.trim().dropLast(1)) else null
                val n = pct?.div(100.0) ?: parseNumber(t)
                if (n != null) {
                    if (pct != null && doc.styles.style(style).numFmt.equals("General", true))
                        style = doc.styles.derive(style, Mod(numFmt = "0%"))
                    Cell(Cell.NUM, NumFmt.numToStr(n), null, style)
                } else Cell(Cell.STR, t, null, style)
            }
        }
        if (cell == null) sh.cells.remove(key(r, c)) else sh.cells[key(r, c)] = cell
        sh.ensureCapacity(r, c)
        touch(doc, sh)
    }

    fun clear(doc: XlsxDoc, sh: SheetData, r1: Int, c1: Int, r2: Int, c2: Int) {
        for (r in r1..r2) for (c in c1..c2) {
            val old = sh.cell(r, c) ?: continue
            if (old.style != 0) sh.cells[key(r, c)] = Cell(Cell.EMPTY, "", null, old.style) else sh.cells.remove(key(r, c))
        }
        touch(doc, sh)
    }

    fun applyStyle(doc: XlsxDoc, sh: SheetData, r1: Int, c1: Int, r2: Int, c2: Int, mod: Mod) {
        for (r in r1..r2) for (c in c1..c2) {
            val old = sh.cell(r, c)
            val ns = doc.styles.derive(old?.style ?: 0, mod)
            sh.cells[key(r, c)] = if (old == null) Cell(Cell.EMPTY, "", null, ns)
            else Cell(old.kind, old.text, old.formula, ns, old.cKind, old.cText)
        }
        touch(doc, sh)
    }

    private fun withFormula(c: Cell, f: String?) =
        Cell(c.kind, c.text, f, c.style, c.cKind, c.cText)

    // ------------------------------------------------------------ insertar / eliminar filas y columnas
    /** delta > 0: inserta delta filas en 'at'. delta < 0: elimina -delta filas desde 'at'. */
    fun shiftRows(doc: XlsxDoc, sh: SheetData, at: Int, delta: Int) = shift(doc, sh, at, delta, true)
    fun shiftCols(doc: XlsxDoc, sh: SheetData, at: Int, delta: Int) = shift(doc, sh, at, delta, false)

    private fun shift(doc: XlsxDoc, sh: SheetData, at: Int, delta: Int, rows: Boolean) {
        val n = abs(delta)
        val del = delta < 0
        fun map(i: Int): Int = if (!del) (if (i >= at) i + n else i)
        else (if (i < at) i else if (i >= at + n) i - n else -1)
        val nc = HashMap<Long, Cell>()
        for ((k, cell) in sh.cells) {
            var r = rowOf(k); var c = colOf(k)
            if (rows) r = map(r) else c = map(c)
            if (r >= 0 && c >= 0) nc[key(r, c)] = cell
        }
        sh.cells = nc
        if (rows) {
            val na = HashMap<Int, String>()
            for ((r, a) in sh.rowAttrs) { val m = map(r); if (m >= 0) na[m] = a }
            sh.rowAttrs = na
        } else {
            val ncols = ArrayList<ColDef>()
            for (cd in sh.cols) {
                val a = map(cd.min); val b = map(cd.max)
                val lo = if (a >= 0) a else at
                val hi = if (b >= 0) b else at - 1
                if (hi >= lo) ncols.add(ColDef(lo, hi, cd.attrs))
            }
            sh.cols = ncols
        }
        val nm = ArrayList<IntArray>()
        for (m in sh.merges) {
            val i1 = if (rows) m[0] else m[1]
            val i2 = if (rows) m[2] else m[3]
            val a = map(i1).let { if (it >= 0) it else at }
            val b = map(i2).let { if (it >= 0) it else at - 1 }
            if (b < a) continue
            nm.add(if (rows) intArrayOf(a, m[1], b, m[3]) else intArrayOf(m[0], a, m[2], b))
        }
        sh.merges = nm
        // referencias en fórmulas de todas las hojas
        for (s2 in doc.sheets) {
            val self = s2 === sh
            var changed = false
            val upd = HashMap<Long, Cell>()
            for ((k, cell) in s2.cells) {
                val f = cell.formula ?: continue
                val nf = Refs.transform(f) { sheet, col, row, _, _ ->
                    val target = if (sheet == null) self else sheet.equals(sh.name, true)
                    if (!target) null
                    else if (rows) col to map(row).let { if (it >= 0) it else at }
                    else map(col).let { if (it >= 0) it else at } to row
                }
                if (nf != f) { upd[k] = withFormula(cell, nf); changed = true }
            }
            if (changed) { s2.cells.putAll(upd); s2.dirty = true }
        }
        if (rows) sh.rows = minOf(5000, sh.rows + if (del) 0 else n) else sh.colsCap = minOf(100, sh.colsCap + if (del) 0 else n)
        touch(doc, sh)
    }

    // ------------------------------------------------------------ combinar celdas / filas ocultas
    private fun overlaps(m: IntArray, r1: Int, c1: Int, r2: Int, c2: Int) =
        m[0] <= r2 && m[2] >= r1 && m[1] <= c2 && m[3] >= c1

    fun mergeAt(sh: SheetData, r: Int, c: Int): IntArray? =
        sh.merges.firstOrNull { r in it[0]..it[2] && c in it[1]..it[3] }

    fun merge(doc: XlsxDoc, sh: SheetData, r1: Int, c1: Int, r2: Int, c2: Int) {
        if (r1 == r2 && c1 == c2) return
        sh.merges.removeAll { overlaps(it, r1, c1, r2, c2) }
        for (r in r1..r2) for (c in c1..c2) if (r != r1 || c != c1) sh.cells.remove(key(r, c))
        sh.merges.add(intArrayOf(r1, c1, r2, c2))
        touch(doc, sh)
    }

    fun unmerge(doc: XlsxDoc, sh: SheetData, r1: Int, c1: Int, r2: Int, c2: Int) {
        sh.merges.removeAll { overlaps(it, r1, c1, r2, c2) }
        touch(doc, sh)
    }

    fun isRowHidden(sh: SheetData, r: Int) = sh.rowAttrs[r]?.contains("hidden=\"1\"") == true

    fun setRowHidden(sh: SheetData, r: Int, hidden: Boolean) {
        val cleaned = (sh.rowAttrs[r] ?: "").replace(Regex("\\shidden=\"[^\"]*\""), "")
        val out = if (hidden) "$cleaned hidden=\"1\"" else cleaned
        if (out.isBlank()) sh.rowAttrs.remove(r) else sh.rowAttrs[r] = out
    }

    /** Muestra solo las filas de datos cuyo valor en keyCol está en `visible` (r1 = primera fila de datos). */
    fun filterRows(doc: XlsxDoc, sh: SheetData, r1: Int, r2: Int, keyCol: Int, visible: Set<String>) {
        for (r in r1..r2) setRowHidden(sh, r, doc.display(sh, r, keyCol) !in visible)
        touch(doc, sh)
    }

    fun showAllRows(doc: XlsxDoc, sh: SheetData) {
        for (r in sh.rowAttrs.keys.toList()) setRowHidden(sh, r, false)
        touch(doc, sh)
    }

    // ------------------------------------------------------------ anchos, alturas, ocultar y rellenar
    /** Reescribe los atributos de las columnas c1..c2 (una entrada por columna). */
    private fun updateCols(sh: SheetData, c1: Int, c2: Int, transform: (String?) -> String) {
        val out = ArrayList<ColDef>()
        for (cd in sh.cols) {
            if (cd.max < c1 || cd.min > c2) { out.add(cd); continue }
            if (cd.min < c1) out.add(ColDef(cd.min, c1 - 1, cd.attrs))
            if (cd.max > c2) out.add(ColDef(c2 + 1, cd.max, cd.attrs))
        }
        for (c in c1..c2) out.add(ColDef(c, c, transform(sh.cols.firstOrNull { c in it.min..it.max }?.attrs)))
        sh.cols = ArrayList(out.sortedBy { it.min })
    }

    private fun stripAttrs(a: String?, vararg names: String): String {
        var r = a ?: ""
        for (n in names) r = r.replace(Regex("\\s$n=\"[^\"]*\""), "")
        return r
    }

    fun setColWidth(doc: XlsxDoc, sh: SheetData, c1: Int, c2: Int, chars: Float) {
        val w = String.format(java.util.Locale.US, "%.2f", chars)
        updateCols(sh, c1, c2) { old -> stripAttrs(old, "width", "customWidth", "hidden") + " width=\"$w\" customWidth=\"1\"" }
        touch(doc, sh)
    }

    fun setColHidden(doc: XlsxDoc, sh: SheetData, c1: Int, c2: Int, hidden: Boolean) {
        updateCols(sh, c1, c2) { old ->
            val base = stripAttrs(old, "hidden")
            val withW = if (Regex("\\swidth=").containsMatchIn(base)) base else "$base width=\"${sh.defColChars}\""
            if (hidden) "$withW hidden=\"1\"" else withW
        }
        touch(doc, sh)
    }

    fun showAllCols(doc: XlsxDoc, sh: SheetData) {
        sh.cols = ArrayList(sh.cols.map { ColDef(it.min, it.max, stripAttrs(it.attrs, "hidden")) })
        touch(doc, sh)
    }

    fun setRowHeight(doc: XlsxDoc, sh: SheetData, r1: Int, r2: Int, pt: Float) {
        val h = String.format(java.util.Locale.US, "%.2f", pt)
        for (r in r1..r2) sh.rowAttrs[r] = stripAttrs(sh.rowAttrs[r], "ht", "customHeight") + " ht=\"$h\" customHeight=\"1\""
        touch(doc, sh)
    }

    fun hideRows(doc: XlsxDoc, sh: SheetData, r1: Int, r2: Int) {
        for (r in r1..r2) setRowHidden(sh, r, true)
        touch(doc, sh)
    }

    private fun putFill(sh: SheetData, r: Int, c: Int, src: Cell?, dr: Int, dc: Int) {
        if (src == null) sh.cells.remove(key(r, c))
        else sh.cells[key(r, c)] = if (src.formula != null) withFormula(src, Refs.translate(src.formula, dr, dc)) else src
    }

    /** Rellenar hacia abajo / hacia la derecha copiando la primera fila / columna del rango. */
    fun fill(doc: XlsxDoc, sh: SheetData, r1: Int, c1: Int, r2: Int, c2: Int, down: Boolean) {
        if (down) for (c in c1..c2) { val src = sh.cell(r1, c); for (r in r1 + 1..r2) putFill(sh, r, c, src, r - r1, 0) }
        else for (r in r1..r2) { val src = sh.cell(r, c1); for (c in c1 + 1..c2) putFill(sh, r, c, src, 0, c - c1) }
        touch(doc, sh)
    }

    // ------------------------------------------------------------ ordenar
    fun currentRegion(sh: SheetData, r: Int, c: Int): IntArray {
        var r1 = r; var r2 = r; var c1 = c; var c2 = c
        fun nonEmpty(a: Int, b: Int) = sh.cell(a, b)?.let { it.kind != Cell.EMPTY || it.formula != null } == true
        var grown = true
        while (grown) {
            grown = false
            if (r1 > 0 && (c1..c2).any { nonEmpty(r1 - 1, it) }) { r1--; grown = true }
            if ((c1..c2).any { nonEmpty(r2 + 1, it) }) { r2++; grown = true }
            if (c1 > 0 && (r1..r2).any { nonEmpty(it, c1 - 1) }) { c1--; grown = true }
            if ((r1..r2).any { nonEmpty(it, c2 + 1) }) { c2++; grown = true }
            if (r2 > 5000 || c2 > 200) break
        }
        return intArrayOf(r1, c1, r2, c2)
    }

    fun sort(doc: XlsxDoc, sh: SheetData, r1: Int, c1: Int, r2: Int, c2: Int, keyCol: Int, asc: Boolean) {
        if (r2 <= r1) return
        val keys = (r1..r2).associateWith { doc.value(sh, it, keyCol) }
        val data = (r1..r2).associateWith { r -> (c1..c2).map { c -> sh.cell(r, c) } }
        val order = (r1..r2).sortedWith(Comparator { a, b ->
            val x = keys[a]; val y = keys[b]
            val bx = x == null || x == ""; val by = y == null || y == ""
            when {
                bx && by -> 0
                bx -> 1
                by -> -1
                else -> {
                    val rx = if (x is Double) 0 else 1; val ry = if (y is Double) 0 else 1
                    val cmp = if (rx != ry) rx.compareTo(ry) else when (x) {
                        is Double -> x.compareTo(y as Double)
                        else -> x.toString().compareTo(y.toString(), ignoreCase = true)
                    }
                    if (asc) cmp else -cmp
                }
            }
        })
        for ((i, src) in order.withIndex()) {
            val dst = r1 + i
            for ((j, c) in (c1..c2).withIndex()) {
                val cell = data[src]!![j]
                if (cell == null) sh.cells.remove(key(dst, c))
                else sh.cells[key(dst, c)] = if (cell.formula != null) withFormula(cell, Refs.translate(cell.formula, dst - src, 0)) else cell
            }
        }
        touch(doc, sh)
    }

    // ------------------------------------------------------------ copiar / pegar
    fun copy(doc: XlsxDoc, sh: SheetData, r1: Int, c1: Int, r2: Int, c2: Int, cut: Boolean): XClip {
        val cells = HashMap<Long, Cell>()
        val tsv = StringBuilder()
        for (r in r1..r2) {
            for (c in c1..c2) {
                sh.cell(r, c)?.let { cells[key(r - r1, c - c1)] = it }
                tsv.append(doc.display(sh, r, c))
                if (c < c2) tsv.append('\t')
            }
            if (r < r2) tsv.append('\n')
        }
        return XClip(cells, r2 - r1 + 1, c2 - c1 + 1, tsv.toString(), cut, sh, r1, c1)
    }

    fun paste(doc: XlsxDoc, sh: SheetData, clip: XClip, r: Int, c: Int) {
        val dr = r - clip.sr
        val dc = c - clip.sc
        for (i in 0 until clip.rows) for (j in 0 until clip.cols) {
            val cell = clip.cells[key(i, j)]
            val tk = key(r + i, c + j)
            if (cell == null) sh.cells.remove(tk)
            else sh.cells[tk] = if (cell.formula != null) withFormula(cell, Refs.translate(cell.formula, dr, dc)) else cell
        }
        sh.ensureCapacity(r + clip.rows, c + clip.cols)
        if (clip.cut) clear(doc, clip.srcSheet, clip.sr, clip.sc, clip.sr + clip.rows - 1, clip.sc + clip.cols - 1)
        touch(doc, sh)
    }

    fun pasteText(doc: XlsxDoc, sh: SheetData, text: String, r: Int, c: Int) {
        val lines = text.replace("\r", "").split('\n')
        for ((i, line) in lines.withIndex()) for ((j, v) in line.split('\t').withIndex()) setInput(doc, sh, r + i, c + j, v)
    }

    // ------------------------------------------------------------ autosuma
    fun autoFunction(doc: XlsxDoc, sh: SheetData, fn: String, r1: Int, c1: Int, r2: Int, c2: Int): Pair<Int, Int> {
        fun isNum(r: Int, c: Int) = sh.cell(r, c)?.let { it.kind == Cell.NUM && it.formula == null || it.formula != null } == true
        if (r1 != r2 || c1 != c2) {
            if (r2 > r1) {
                for (c in c1..c2) setInput(doc, sh, r2 + 1, c, "=$fn(${Refs.addr(r1, c)}:${Refs.addr(r2, c)})")
                return (r2 + 1) to c1
            }
            for (r in r1..r2) setInput(doc, sh, r, c2 + 1, "=$fn(${Refs.addr(r, c1)}:${Refs.addr(r, c2)})")
            return r1 to (c2 + 1)
        }
        var n = 0
        while (r1 - n - 1 >= 0 && isNum(r1 - n - 1, c1)) n++
        if (n > 0) {
            setInput(doc, sh, r1, c1, "=$fn(${Refs.addr(r1 - n, c1)}:${Refs.addr(r1 - 1, c1)})")
            return r1 to c1
        }
        n = 0
        while (c1 - n - 1 >= 0 && isNum(r1, c1 - n - 1)) n++
        if (n > 0) setInput(doc, sh, r1, c1, "=$fn(${Refs.addr(r1, c1 - n)}:${Refs.addr(r1, c1 - 1)})")
        else setInput(doc, sh, r1, c1, "=$fn()")
        return r1 to c1
    }
}
