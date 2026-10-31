package com.docreader.editor.data.docx

import android.text.SpannableStringBuilder

class PProps(
    var style: String? = null,
    var jc: String? = null,
    var numId: Int? = null,
    var ilvl: Int = 0,
    var line: Int? = null,
    var indLeft: Int? = null,
    var spacingXml: String? = null,
    var indXml: String? = null,
    var others: List<Pair<String, String>> = emptyList()
) {
    var lineChanged = false
    var indChanged = false

    fun copy(): PProps = PProps(style, jc, numId, ilvl, line, indLeft, spacingXml, indXml, others).also {
        it.lineChanged = lineChanged
        it.indChanged = indChanged
    }

    /** 0 = Título, 1..9 = Título n, -1 = texto normal. */
    fun headingLevel(): Int {
        val s = style ?: return -1
        if (s == "Title") return 0
        return Regex("^Heading(\\d)$").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }
}

class ImageData(val bytes: ByteArray, val ext: String, val cx: Long, val cy: Long) {
    var id: Int = 0
    val relId get() = "rIdDRImg$id"
    val fileName get() = "dr_img_$id.$ext"
}

sealed class Block {
    var dirty = false
    open val hidden: Boolean get() = false
    abstract fun deepCopy(): Block
}

class ParaBlock(
    var text: SpannableStringBuilder,
    var props: PProps,
    val openTag: String?,
    val raw: String?
) : Block() {
    override fun deepCopy(): Block =
        ParaBlock(Fmt.copyText(text), props.copy(), openTag, raw).also { it.dirty = dirty }
}

/** Contenido que no se edita (párrafos con campos/imágenes, controles, marcadores...). Se conserva tal cual. */
class RawBlock(val xml: String, val label: String, override val hidden: Boolean = false) : Block() {
    override fun deepCopy(): Block = this
}

class ImageBlock(val image: ImageData) : Block() {
    override fun deepCopy(): Block = this
}

class CellData(
    var tcPr: String,
    var text: SpannableStringBuilder,
    var props: PProps,
    var locked: Boolean,
    var raw: String?,
    var dirty: Boolean = false
)

class RowData(var trPr: String, val cells: MutableList<CellData>)

class TableBlock(
    var tblPr: String,
    val grid: MutableList<Int>,
    val rows: MutableList<RowData>,
    var simple: Boolean,
    val raw: String?
) : Block() {
    override fun deepCopy(): Block = TableBlock(
        tblPr,
        grid.toMutableList(),
        rows.map { r ->
            RowData(r.trPr, r.cells.map { c ->
                CellData(c.tcPr, Fmt.copyText(c.text), c.props.copy(), c.locked, c.raw, c.dirty)
            }.toMutableList())
        }.toMutableList(),
        simple,
        raw
    ).also { it.dirty = dirty }
}

class NumberingInfo(
    private val numToAbs: Map<Int, Int>,
    private val absLevels: Map<Int, Map<Int, String>>
) {
    fun fmt(numId: Int, ilvl: Int): String {
        val lv = absLevels[numToAbs[numId]] ?: return "decimal"
        return lv[ilvl] ?: lv[0] ?: "decimal"
    }

    companion object {
        fun parse(xml: String?): NumberingInfo {
            if (xml == null) return NumberingInfo(emptyMap(), emptyMap())
            val abs = mutableMapOf<Int, Map<Int, String>>()
            val absRe = Regex("<w:abstractNum\\b[^>]*?w:abstractNumId=\"(\\d+)\"[^>]*>(.*?)</w:abstractNum>", RegexOption.DOT_MATCHES_ALL)
            val lvlRe = Regex("<w:lvl\\b[^>]*?w:ilvl=\"(\\d+)\"[^>]*>(.*?)</w:lvl>", RegexOption.DOT_MATCHES_ALL)
            val fmtRe = Regex("<w:numFmt\\s[^>]*?w:val=\"(\\w+)\"")
            for (m in absRe.findAll(xml)) {
                val lv = mutableMapOf<Int, String>()
                for (l in lvlRe.findAll(m.groupValues[2])) {
                    val f = fmtRe.find(l.groupValues[2])?.groupValues?.get(1) ?: "decimal"
                    lv[l.groupValues[1].toInt()] = f
                }
                abs[m.groupValues[1].toInt()] = lv
            }
            val nums = mutableMapOf<Int, Int>()
            val numRe = Regex("<w:num\\b[^>]*?w:numId=\"(\\d+)\"[^>]*>(.*?)</w:num>", RegexOption.DOT_MATCHES_ALL)
            for (m in numRe.findAll(xml)) {
                val a = Regex("<w:abstractNumId\\s[^>]*?w:val=\"(\\d+)\"").find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull()
                if (a != null) nums[m.groupValues[1].toInt()] = a
            }
            return NumberingInfo(nums, abs)
        }
    }
}

class DocxDoc(
    val bytes: ByteArray,
    val prefix: String,
    val suffix: String,
    val trailer: String,
    val blocks: MutableList<Block>,
    val numbering: NumberingInfo,
    val styles: DocxStyles = DocxStyles.EMPTY
) {
    val undo = ArrayDeque<List<Block>>()
    val redo = ArrayDeque<List<Block>>()
    var seq = 0
    /** true si hay cambios sin guardar. */
    var modified = false

    fun snapshot(): List<Block> = blocks.map { it.deepCopy() }

    fun plainText(): String = blocks.joinToString("\n") { b ->
        when (b) {
            is ParaBlock -> b.text.toString()
            is TableBlock -> b.rows.joinToString("\n") { r -> r.cells.joinToString("\t") { it.text.toString() } }
            is RawBlock -> if (b.hidden) "" else b.label
            is ImageBlock -> ""
        }
    }
}
