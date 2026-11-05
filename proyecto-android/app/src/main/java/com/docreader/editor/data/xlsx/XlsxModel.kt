package com.docreader.editor.data.xlsx

/** Celda inmutable (se reemplaza al editar, así los snapshots de deshacer son baratos). */
class Cell(
    val kind: Int,
    val text: String,
    val formula: String?,
    val style: Int,
    val cKind: Int = -1,          // resultado en caché del archivo (si es fórmula)
    val cText: String? = null
) {
    companion object {
        const val NUM = 0
        const val STR = 1
        const val BOOL = 2
        const val ERR = 3
        const val EMPTY = 4
    }
}

class ColDef(val min: Int, val max: Int, val attrs: String)

fun key(r: Int, c: Int): Long = (r.toLong() shl 20) or c.toLong()
fun rowOf(k: Long): Int = (k shr 20).toInt()
fun colOf(k: Long): Int = (k and 0xFFFFF).toInt()

class SheetData(var name: String, val path: String, val isNew: Boolean = false) {
    val origName: String = name
    var cells = HashMap<Long, Cell>()
    var cols = ArrayList<ColDef>()
    var merges = ArrayList<IntArray>()          // r1, c1, r2, c2
    var rowAttrs = HashMap<Int, String>()
    var head = ""
    var tailBefore = ""
    var tailAfter = ""
    var hasMergeSlot = false
    var origXml: String? = null
    var dirty = false
    var rows = 30
    var colsCap = 10
    var freezeR = 0
    var freezeC = 0
    var freezeDirty = false
    var defColChars = 8.43f
    var defRowPt = 15f
    val calc = HashMap<Long, Any?>()

    fun cell(r: Int, c: Int): Cell? = cells[key(r, c)]

    fun maxRow(): Int = cells.keys.maxOfOrNull { rowOf(it) } ?: -1
    fun maxCol(): Int = cells.keys.maxOfOrNull { colOf(it) } ?: -1

    fun ensureCapacity(r: Int, c: Int) {
        if (r >= rows - 3) rows = minOf(5000, maxOf(rows, r + 20))
        if (c >= colsCap - 1) colsCap = minOf(100, maxOf(colsCap, c + 5))
    }

    companion object {
        fun blank(name: String, path: String): SheetData = SheetData(name, path, true).also {
            it.head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            it.tailBefore = "</worksheet>"
            it.dirty = true
        }
    }
}

class SheetSnap(
    val sheet: SheetData,
    val cells: HashMap<Long, Cell>,
    val cols: List<ColDef>,
    val merges: List<IntArray>,
    val rowAttrs: HashMap<Int, String>,
    val name: String
)

class XlsxDoc(
    val bytes: ByteArray,
    val sheets: MutableList<SheetData>,
    val styles: StyleBook
) {
    val eval = FormulaEngine(this)
    var isCsv = false
    var modified = false
    var csvDelimiter = ','
    var csvBom = false
    val undo = ArrayDeque<List<SheetSnap>>()
    val redo = ArrayDeque<List<SheetSnap>>()

    /** Contador que sube con cada cambio (para invalidar cachés de la vista). */
    var ver = 0

    fun invalidate() { for (s in sheets) s.calc.clear(); ver++ }

    fun snapshot(): List<SheetSnap> = sheets.map {
        SheetSnap(it, HashMap(it.cells), ArrayList(it.cols), it.merges.map { m -> m.clone() }, HashMap(it.rowAttrs), it.name)
    }

    fun restore(snaps: List<SheetSnap>) {
        for (sn in snaps) {
            val sh = sn.sheet
            sh.cells = HashMap(sn.cells)
            sh.cols = ArrayList(sn.cols)
            sh.merges = ArrayList(sn.merges.map { it.clone() })
            sh.rowAttrs = HashMap(sn.rowAttrs)
            sh.name = sn.name
            sh.dirty = true
        }
        invalidate()
    }

    fun value(sh: SheetData, r: Int, c: Int): Any? = eval.cellValue(sh, r, c)

    fun display(sh: SheetData, r: Int, c: Int): String {
        val cell = sh.cell(r, c) ?: return ""
        return when (val v = value(sh, r, c)) {
            null -> ""
            is Double -> NumFmt.format(v, styles.style(cell.style).numFmt)
            is Boolean -> if (v) "TRUE" else "FALSE"
            is FErr -> v.code
            else -> v.toString()
        }
    }

    /** Texto editable de la celda (fórmula con "=" o valor). */
    fun raw(sh: SheetData, r: Int, c: Int): String {
        val cell = sh.cell(r, c) ?: return ""
        if (cell.formula != null) return "=" + cell.formula
        return when (cell.kind) {
            Cell.BOOL -> if (cell.text == "1") "TRUE" else "FALSE"
            Cell.EMPTY -> ""
            else -> cell.text
        }
    }

    fun plainText(): String {
        val sb = StringBuilder()
        for (s in sheets) {
            for ((k, cell) in s.cells) {
                if (cell.kind == Cell.STR && cell.text.isNotBlank()) sb.append(cell.text).append(' ')
                else if (cell.kind == Cell.NUM && cell.formula == null) sb.append(cell.text).append(' ')
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
