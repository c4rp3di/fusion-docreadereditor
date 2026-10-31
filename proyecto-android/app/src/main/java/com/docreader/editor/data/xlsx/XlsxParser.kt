package com.docreader.editor.data.xlsx

import com.docreader.editor.data.docx.X
import com.docreader.editor.data.engine.OoxmlZip

object XlsxParser {
    private val sheetDataRe = Regex("<sheetData(?:\\s[^>]*)?(?:/>|>(.*?)</sheetData>)", RegexOption.DOT_MATCHES_ALL)
    private val rowRe = Regex("<row\\s([^>]*?)(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL)
    private val cellRe = Regex("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val fRe = Regex("<f(?:\\s([^>]*?))?(?:/>|>([^<]*)</f>)")
    private val vRe = Regex("<v>([^<]*)</v>")
    private val tRe = Regex("<t(?:\\s[^>]*)?>([^<]*)</t>")

    fun parse(bytes: ByteArray): XlsxDoc {
        val files = OoxmlZip.read(bytes) {
            it == "xl/workbook.xml" || it == "xl/_rels/workbook.xml.rels" || it == "xl/sharedStrings.xml" ||
                it == "xl/styles.xml" || (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        }
        val shared = files["xl/sharedStrings.xml"]?.let { parseShared(it.toString(Charsets.UTF_8)) } ?: emptyList()
        val styleXml = files["xl/styles.xml"]?.toString(Charsets.UTF_8)
        val styles = StyleBook(styleXml, styleXml != null)
        val sheets = mutableListOf<SheetData>()
        for ((name, path) in sheetList(files)) {
            val xml = files[path]?.toString(Charsets.UTF_8) ?: continue
            sheets.add(parseSheet(name, path, xml, shared))
        }
        if (sheets.isEmpty()) throw java.io.IOException("XLSX no válido")
        return XlsxDoc(bytes, sheets, styles)
    }

    private fun sheetList(files: Map<String, ByteArray>): List<Pair<String, String>> {
        val wb = files["xl/workbook.xml"]?.toString(Charsets.UTF_8)
        val rels = files["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)
        if (wb != null && rels != null) {
            val targets = Regex("<Relationship\\s[^>]*?/>").findAll(rels).associate {
                val id = OoxmlZip.attr(it.value, "Id") ?: ""
                val t = OoxmlZip.attr(it.value, "Target") ?: ""
                id to (if (t.startsWith("/")) t.removePrefix("/") else "xl/$t")
            }
            val list = Regex("<sheet\\s[^>]*?/>").findAll(wb).mapNotNull {
                val name = OoxmlZip.attr(it.value, "name")?.let(OoxmlZip::unescape) ?: return@mapNotNull null
                val rid = OoxmlZip.attr(it.value, "id") ?: return@mapNotNull null
                val path = targets[rid] ?: return@mapNotNull null
                name to path
            }.toList()
            if (list.isNotEmpty()) return list
        }
        return files.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted()
            .mapIndexed { i, p -> "Hoja${i + 1}" to p }
    }

    private fun parseShared(xml: String): List<String> =
        Regex("<si>(.*?)</si>|<si\\s[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
            val body = si.groupValues[1].ifEmpty { si.groupValues[2] }
            tRe.findAll(body).joinToString("") { OoxmlZip.unescape(it.groupValues[1]) }
        }.toList()

    private fun parseSheet(name: String, path: String, xml: String, shared: List<String>): SheetData {
        val sh = SheetData(name, path)
        sh.origXml = xml
        val sd = sheetDataRe.find(xml) ?: run {
            sh.head = xml.substringBefore("</worksheet>")
            sh.tailBefore = "</worksheet>"
            return sh
        }
        var head = xml.substring(0, sd.range.first)
        val tail = xml.substring(sd.range.last + 1)

        Regex("<cols>(.*?)</cols>", RegexOption.DOT_MATCHES_ALL).find(head)?.let { m ->
            for (c in Regex("<col\\s[^>]*?/>").findAll(m.groupValues[1])) {
                val a = X.attrs(c.value)
                val mn = a["min"]?.toIntOrNull() ?: continue
                val mx = a["max"]?.toIntOrNull() ?: mn
                val rest = a.entries.filter { it.key != "min" && it.key != "max" }.joinToString("") { " ${it.key}=\"${it.value}\"" }
                sh.cols.add(ColDef(mn - 1, mx - 1, rest))
            }
            head = head.removeRange(m.range)
        }
        sh.head = head

        val mm = Regex("<mergeCells\\b[^>]*>(.*?)</mergeCells>", RegexOption.DOT_MATCHES_ALL).find(tail)
        if (mm != null) {
            for (c in Regex("<mergeCell\\s[^>]*?/>").findAll(mm.groupValues[1])) {
                val ref = X.attrs(c.value)["ref"] ?: continue
                val parts = ref.split(":")
                val a = Regex("([A-Za-z]+)(\\d+)").find(parts[0]) ?: continue
                val b = Regex("([A-Za-z]+)(\\d+)").find(parts.getOrElse(1) { parts[0] }) ?: continue
                sh.merges.add(intArrayOf(a.groupValues[2].toInt() - 1, Refs.colToIdx(a.groupValues[1]),
                    b.groupValues[2].toInt() - 1, Refs.colToIdx(b.groupValues[1])))
            }
            sh.tailBefore = tail.substring(0, mm.range.first)
            sh.tailAfter = tail.substring(mm.range.last + 1)
            sh.hasMergeSlot = true
        } else {
            sh.tailBefore = tail
        }

        val sharedF = HashMap<String, Triple<Int, Int, String>>()
        var maxR = 0
        var maxC = 0
        for (rm in rowRe.findAll(sd.groupValues[1])) {
            val rattrs = rm.groupValues[1]
            val rnum = OoxmlZip.attr(rattrs, "r")?.toIntOrNull()?.minus(1) ?: continue
            val extra = X.attrs("<row $rattrs>").entries.filter { it.key != "r" && it.key != "spans" }
                .joinToString("") { " ${it.key}=\"${it.value}\"" }
            if (extra.isNotBlank()) sh.rowAttrs[rnum] = extra
            maxR = maxOf(maxR, rnum + 1)
            for (cm in cellRe.findAll(rm.groupValues[2])) {
                val a = X.attrs("<c ${cm.groupValues[1]}>")
                val ref = a["r"] ?: continue
                val rm2 = Regex("([A-Za-z]+)(\\d+)").find(ref) ?: continue
                val col = Refs.colToIdx(rm2.groupValues[1])
                val row = rm2.groupValues[2].toInt() - 1
                val t = a["t"]
                val style = a["s"]?.toIntOrNull() ?: 0
                val inner = cm.groupValues[2]
                val v = vRe.find(inner)?.groupValues?.get(1)?.let(OoxmlZip::unescape)
                val fm = fRe.find(inner)
                var formula: String? = null
                if (fm != null) {
                    val fa = X.attrs("<f ${fm.groupValues[1]}>")
                    val ftxt = OoxmlZip.unescape(fm.groupValues[2])
                    if (fa["t"] == "shared") {
                        val si = fa["si"] ?: ""
                        if (ftxt.isNotEmpty()) { sharedF[si] = Triple(row, col, ftxt); formula = ftxt }
                        else sharedF[si]?.let { (mr, mc, mf) -> formula = Refs.translate(mf, row - mr, col - mc) }
                    } else if (ftxt.isNotEmpty()) formula = ftxt
                }
                val cell: Cell = when {
                    formula != null -> {
                        val ck = when (t) { "str" -> Cell.STR; "b" -> Cell.BOOL; "e" -> Cell.ERR; null, "n" -> if (v != null) Cell.NUM else -1; else -> Cell.STR }
                        Cell(Cell.NUM, "", formula, style, ck, v)
                    }
                    t == "s" -> Cell(Cell.STR, shared.getOrElse(v?.trim()?.toIntOrNull() ?: -1) { "" }, null, style)
                    t == "inlineStr" -> Cell(Cell.STR, tRe.findAll(inner).joinToString("") { OoxmlZip.unescape(it.groupValues[1]) }, null, style)
                    t == "str" -> Cell(Cell.STR, v ?: "", null, style)
                    t == "b" -> Cell(Cell.BOOL, if (v == "1") "1" else "0", null, style)
                    t == "e" -> Cell(Cell.ERR, v ?: "#VALUE!", null, style)
                    v != null && v.isNotEmpty() -> Cell(Cell.NUM, v, null, style)
                    style != 0 -> Cell(Cell.EMPTY, "", null, style)
                    else -> continue
                }
                sh.cells[key(row, col)] = cell
                maxR = maxOf(maxR, row + 1)
                maxC = maxOf(maxC, col + 1)
            }
        }
        sh.rows = maxOf(maxR + 15, 30).coerceAtMost(5000)
        sh.colsCap = maxOf(maxC + 3, 10).coerceAtMost(100)
        return sh
    }
}
