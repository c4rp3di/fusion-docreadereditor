package com.docreader.editor.data.docx

import android.graphics.Color
import android.text.SpannableStringBuilder
import com.docreader.editor.data.engine.OoxmlZip

object DocxParser {
    private val lockers = listOf(
        "<w:drawing", "<w:pict", "<w:object", "<w:fldChar", "<w:fldSimple", "<w:instrText", "<w:sdt",
        "<w:pPrChange", "<w:rPrChange", "<w:hyperlink", "<w:footnoteReference", "<w:endnoteReference",
        "<w:commentRangeStart", "<w:sym", "<mc:AlternateContent", "<w:del ", "<w:del>", "<w:moveFrom"
    )
    private val pageBreakRe = Regex("<w:br\\s[^>]*w:type=")
    private val hiddenNames = setOf(
        "bookmarkStart", "bookmarkEnd", "proofErr", "permStart", "permEnd", "commentRangeStart", "commentRangeEnd"
    )
    private val tRe = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>")

    fun parse(bytes: ByteArray): DocxDoc {
        val files = OoxmlZip.read(bytes) { it == "word/document.xml" || it == "word/numbering.xml" || it == "word/styles.xml" }
        val xml = files["word/document.xml"]?.toString(Charsets.UTF_8)
            ?: throw java.io.IOException("DOCX no válido")
        val bs = Regex("<w:body\\s*>").find(xml) ?: throw java.io.IOException("DOCX no válido")
        val be = xml.lastIndexOf("</w:body>")
        if (be < 0) throw java.io.IOException("DOCX no válido")
        val prefix = xml.substring(0, bs.range.last + 1)
        val suffix = xml.substring(be)
        val blocks = mutableListOf<Block>()
        var trailer = ""
        for ((name, raw) in X.children(xml.substring(bs.range.last + 1, be))) {
            when (X.local(name)) {
                "p" -> blocks.add(parsePara(raw))
                "tbl" -> blocks.add(parseTable(raw))
                "sectPr" -> trailer += raw
                else -> blocks.add(RawBlock(raw, label(raw), X.local(name) in hiddenNames))
            }
        }
        if (blocks.none { !it.hidden }) {
            blocks.add(ParaBlock(SpannableStringBuilder(), PProps(), null, null).also { it.dirty = true })
        }
        return DocxDoc(bytes, prefix, suffix, trailer, blocks, NumberingInfo.parse(files["word/numbering.xml"]?.toString(Charsets.UTF_8)),
            DocxStyles.parse(files["word/styles.xml"]?.toString(Charsets.UTF_8)))
    }

    private fun label(raw: String): String {
        val t = tRe.findAll(raw).joinToString("") { OoxmlZip.unescape(it.groupValues[1]) }.trim()
        if (t.isNotEmpty()) return t.take(120)
        return if (raw.contains("<w:drawing") || raw.contains("<w:pict") || raw.contains("<w:object"))
            "[Imagen u objeto]" else "[Contenido no editable]"
    }

    private fun isLocked(raw: String) = lockers.any { raw.contains(it) } || pageBreakRe.containsMatchIn(raw)

    fun parsePara(raw: String): Block {
        if (isLocked(raw)) return RawBlock(raw, label(raw))
        val open = X.openTag(raw)
        if (open.endsWith("/>")) return ParaBlock(SpannableStringBuilder(), PProps(), null, raw)
        val sb = SpannableStringBuilder()
        var props = PProps()
        for ((n, x) in X.children(X.inner(raw))) {
            when (X.local(n)) {
                "pPr" -> props = parsePPr(x)
                "r" -> appendRun(sb, x)
                "ins" -> for ((n2, x2) in X.children(X.inner(x))) {
                    if (X.local(n2) == "r") appendRun(sb, x2)
                }
            }
        }
        return ParaBlock(sb, props, open, raw)
    }

    private fun parsePPr(x: String): PProps {
        val p = PProps()
        val others = ArrayList<Pair<String, String>>()
        for ((n, c) in X.children(X.inner(x))) {
            when (X.local(n)) {
                "pStyle" -> p.style = X.attr(c, "val")
                "jc" -> p.jc = when (X.attr(c, "val")) {
                    "center" -> "center"
                    "right", "end" -> "right"
                    "both", "distribute" -> "both"
                    else -> "left"
                }
                "numPr" -> {
                    for ((n2, c2) in X.children(X.inner(c))) {
                        when (X.local(n2)) {
                            "ilvl" -> p.ilvl = X.attr(c2, "val")?.toIntOrNull() ?: 0
                            "numId" -> p.numId = X.attr(c2, "val")?.toIntOrNull()
                        }
                    }
                    if (p.numId == 0) p.numId = null
                }
                "spacing" -> { p.spacingXml = c; p.line = X.attr(c, "line")?.toIntOrNull() }
                "ind" -> { p.indXml = c; p.indLeft = (X.attr(c, "left") ?: X.attr(c, "start"))?.toIntOrNull() }
                else -> others.add(n to c)
            }
        }
        p.others = others
        return p
    }

    private fun on(c: String): Boolean {
        val v = X.attr(c, "val") ?: return true
        return v != "0" && v != "false" && v != "off"
    }

    private fun appendRun(sb: SpannableStringBuilder, x: String) {
        val kids = X.children(X.inner(x))
        val start = sb.length
        for ((n, c) in kids) {
            when (X.local(n)) {
                "t" -> sb.append(OoxmlZip.unescape(X.inner(c)))
                "tab" -> sb.append('\t')
                "br", "cr" -> sb.append('\n')
                "noBreakHyphen" -> sb.append('-')
            }
        }
        val end = sb.length
        if (end <= start) return
        val rpr = kids.firstOrNull { X.local(it.first) == "rPr" } ?: return
        applyRPr(sb, start, end, X.children(X.inner(rpr.second)))
    }

    private fun applyRPr(sb: SpannableStringBuilder, s: Int, e: Int, kids: List<Pair<String, String>>) {
        val extras = ArrayList<Pair<String, String>>()
        for ((n, c) in kids) {
            when (X.local(n)) {
                "b" -> if (on(c)) sb.setSpan(BoldSpan(), s, e, Fmt.EI)
                "i" -> if (on(c)) sb.setSpan(ItalicSpan(), s, e, Fmt.EI)
                "u" -> if (X.attr(c, "val") != "none") sb.setSpan(UlSpan(), s, e, Fmt.EI)
                "strike" -> if (on(c)) sb.setSpan(StrSpan(), s, e, Fmt.EI)
                "sz" -> X.attr(c, "val")?.toFloatOrNull()?.let { sb.setSpan(SizeSpan(it / 2f), s, e, Fmt.EI) }
                "color" -> {
                    val v = X.attr(c, "val")
                    if (v != null && v.length == 6) {
                        try { sb.setSpan(FgSpan(Color.parseColor("#$v")), s, e, Fmt.EI) } catch (_: Exception) { }
                    }
                }
                "highlight" -> X.attr(c, "val")?.let { if (it != "none") sb.setSpan(BgSpan(Hl.color(it)), s, e, Fmt.EI) }
                "rFonts" -> (X.attr(c, "ascii") ?: X.attr(c, "hAnsi"))?.let { sb.setSpan(FontSpan(it), s, e, Fmt.EI) }
                "bCs", "iCs", "szCs" -> Unit
                else -> extras.add(n to c)
            }
        }
        if (extras.isNotEmpty()) sb.setSpan(ExtraRPr(extras), s, e, Fmt.EI)
    }

    fun parseTable(raw: String): Block {
        val kids = X.children(X.inner(raw))
        val tblPr = kids.firstOrNull { X.local(it.first) == "tblPr" }?.second ?: ""
        val grid = mutableListOf<Int>()
        kids.firstOrNull { X.local(it.first) == "tblGrid" }?.let { g ->
            for ((n, c) in X.children(X.inner(g.second))) {
                if (X.local(n) == "gridCol") grid.add(X.attr(c, "w")?.toIntOrNull() ?: 2000)
            }
        }
        var simple = !(raw.contains("<w:gridSpan") || raw.contains("<w:vMerge") || raw.contains("<w:hMerge") ||
            raw.contains("<w:ins ") || raw.contains("<w:del ") ||
            Regex("<w:tbl[ >]").findAll(raw).count() > 1)
        val rows = mutableListOf<RowData>()
        for ((n, r) in kids) {
            if (X.local(n) != "tr") continue
            val rk = X.children(X.inner(r))
            val trPr = rk.firstOrNull { X.local(it.first) == "trPr" }?.second ?: ""
            val cells = mutableListOf<CellData>()
            for ((n2, c) in rk) {
                if (X.local(n2) == "tc") cells.add(parseCell(c))
                else if (X.local(n2) != "trPr") simple = false
            }
            rows.add(RowData(trPr, cells))
        }
        if (rows.isEmpty()) return RawBlock(raw, "[Tabla]")
        if (grid.isEmpty() || rows.any { it.cells.size != grid.size }) simple = false
        return TableBlock(tblPr, grid, rows, simple, raw)
    }

    private fun parseCell(tc: String): CellData {
        val kids = X.children(X.inner(tc))
        val tcPr = kids.firstOrNull { X.local(it.first) == "tcPr" }?.second ?: ""
        var locked = false
        val text = SpannableStringBuilder()
        var props: PProps? = null
        var first = true
        for ((n, c) in kids) {
            if (X.local(n) == "tcPr") continue
            if (X.local(n) != "p") { locked = true; continue }
            if (!first) text.append('\n')
            first = false
            when (val b = parsePara(c)) {
                is ParaBlock -> { text.append(b.text); if (props == null) props = b.props }
                is RawBlock -> { locked = true; text.append(b.label) }
                else -> Unit
            }
        }
        return CellData(tcPr, text, props ?: PProps(), locked, tc, false)
    }
}
