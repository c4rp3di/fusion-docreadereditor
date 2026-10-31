package com.docreader.editor.data.xlsx

import com.docreader.editor.data.engine.OoxmlZip

object XlsxWriter {
    private fun esc(s: String) = OoxmlZip.escape(s)

    private val slotTags = listOf(
        "<phoneticPr", "<conditionalFormatting", "<dataValidations", "<hyperlinks", "<printOptions",
        "<pageMargins", "<pageSetup", "<headerFooter", "<rowBreaks", "<colBreaks", "<customProperties",
        "<cellWatches", "<ignoredErrors", "<smartTags", "<drawing", "<legacyDrawing", "<legacyDrawingHF",
        "<picture", "<oleObjects", "<controls", "<webPublishItems", "<tableParts", "<extLst", "</worksheet>"
    )

    fun write(doc: XlsxDoc): ByteArray {
        val renamed = doc.sheets.any { it.name != it.origName }
        val dirtySheets = doc.sheets.filter { it.dirty || it.isNew }
        if (dirtySheets.isEmpty() && !doc.styles.dirty && !renamed) return doc.bytes

        val replace = mutableMapOf<String, ByteArray>()
        val add = mutableMapOf<String, ByteArray>()
        val drop = mutableSetOf<String>()
        for (s in dirtySheets) {
            val data = sheetXml(doc, s).toByteArray(Charsets.UTF_8)
            if (s.isNew) add[s.path] = data else replace[s.path] = data
        }
        val parts = OoxmlZip.read(doc.bytes) {
            it == "[Content_Types].xml" || it == "xl/_rels/workbook.xml.rels" || it == "xl/workbook.xml"
        }
        var types = parts["[Content_Types].xml"]?.toString(Charsets.UTF_8)
        var rels = parts["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)
        var wb = parts["xl/workbook.xml"]?.toString(Charsets.UTF_8)

        if (doc.styles.dirty) {
            val sx = doc.styles.toXml().toByteArray(Charsets.UTF_8)
            if (doc.styles.existed) replace["xl/styles.xml"] = sx
            else {
                add["xl/styles.xml"] = sx
                rels = rels?.replace("</Relationships>",
                    "<Relationship Id=\"rIdDRStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
                types = types?.replace("</Types>",
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>")
            }
        }

        if (wb != null) {
            var nextId = (Regex("sheetId=\"(\\d+)\"").findAll(wb).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1
            for (s in doc.sheets) {
                if (s.isNew) {
                    val rid = "rIdDRSheet$nextId"
                    wb = wb!!.replace("</sheets>",
                        "<sheet name=\"${esc(s.name).replace("\"", "&quot;")}\" sheetId=\"$nextId\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"$rid\"/></sheets>")
                    rels = rels?.replace("</Relationships>",
                        "<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"${s.path.removePrefix("xl/")}\"/></Relationships>")
                    types = types?.replace("</Types>",
                        "<Override PartName=\"/${s.path}\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>")
                    nextId++
                } else if (s.name != s.origName) {
                    val oldAttr = "name=\"${esc(s.origName).replace("\"", "&quot;")}\""
                    wb = wb!!.replaceFirst(oldAttr, "name=\"${esc(s.name).replace("\"", "&quot;")}\"")
                }
            }
            // Fórmulas de definedNames con el nombre antiguo
            for (s in doc.sheets) if (!s.isNew && s.name != s.origName) wb = Refs.renameSheet(wb!!, s.origName, s.name)
            // Recalcular al abrir
            val calc = Regex("<calcPr\\b([^>]*?)(/?)>").find(wb!!)
            if (calc != null && !calc.groupValues[1].contains("fullCalcOnLoad")) {
                wb = wb!!.replaceRange(calc.range, "<calcPr${calc.groupValues[1]} fullCalcOnLoad=\"1\"${calc.groupValues[2]}>")
            }
            replace["xl/workbook.xml"] = wb!!.toByteArray(Charsets.UTF_8)
        }

        drop.add("xl/calcChain.xml")
        types = types?.replace(Regex("<Override\\s[^>]*calcChain[^>]*/>"), "")
        rels = rels?.replace(Regex("<Relationship\\s[^>]*calcChain[^>]*/>"), "")
        types?.let { replace["[Content_Types].xml"] = it.toByteArray(Charsets.UTF_8) }
        rels?.let { replace["xl/_rels/workbook.xml.rels"] = it.toByteArray(Charsets.UTF_8) }
        return OoxmlZip.rewrite(doc.bytes, replace, drop, add)
    }

    private fun sheetXml(doc: XlsxDoc, s: SheetData): String {
        val sb = StringBuilder()
        var head = s.head
        val maxR = s.maxRow()
        val maxC = s.maxCol()
        val dim = if (maxR < 0) "A1" else "A1:" + Refs.addr(maxR, maxC)
        head = if (Regex("<dimension\\s[^>]*/>").containsMatchIn(head))
            head.replace(Regex("<dimension\\s[^>]*/>"), "<dimension ref=\"$dim\"/>") else head
        sb.append(head)
        if (s.cols.isNotEmpty()) {
            sb.append("<cols>")
            for (c in s.cols) sb.append("<col min=\"${c.min + 1}\" max=\"${c.max + 1}\"${c.attrs}/>")
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        val byRow = s.cells.entries.groupBy { rowOf(it.key) }
        val rowNums = (byRow.keys + s.rowAttrs.keys).toSortedSet()
        for (r in rowNums) {
            val attrs = s.rowAttrs[r] ?: ""
            val cs = byRow[r]?.sortedBy { colOf(it.key) }
            if (cs == null || cs.isEmpty()) { sb.append("<row r=\"${r + 1}\"$attrs/>"); continue }
            sb.append("<row r=\"${r + 1}\"$attrs>")
            for ((k, cell) in cs) sb.append(cellXml(doc, s, r, colOf(k), cell))
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        val merges = if (s.merges.isEmpty()) "" else
            "<mergeCells count=\"${s.merges.size}\">" + s.merges.joinToString("") {
                "<mergeCell ref=\"${Refs.addr(it[0], it[1])}:${Refs.addr(it[2], it[3])}\"/>"
            } + "</mergeCells>"
        if (s.hasMergeSlot || merges.isEmpty()) {
            sb.append(s.tailBefore).append(merges).append(s.tailAfter)
        } else {
            val pos = slotTags.map { s.tailBefore.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: s.tailBefore.length
            sb.append(s.tailBefore, 0, pos).append(merges).append(s.tailBefore, pos, s.tailBefore.length)
        }
        return sb.toString()
    }

    private fun cellXml(doc: XlsxDoc, s: SheetData, r: Int, c: Int, cell: Cell): String {
        val ref = Refs.addr(r, c)
        val st = if (cell.style != 0) " s=\"${cell.style}\"" else ""
        if (cell.formula != null) {
            val f = "<f>${esc(cell.formula)}</f>"
            return when (val v = doc.value(s, r, c)) {
                is Double -> "<c r=\"$ref\"$st>$f<v>${NumFmt.numToStr(v)}</v></c>"
                is String -> "<c r=\"$ref\"$st t=\"str\">$f<v>${esc(v)}</v></c>"
                is Boolean -> "<c r=\"$ref\"$st t=\"b\">$f<v>${if (v) 1 else 0}</v></c>"
                is FErr -> "<c r=\"$ref\"$st t=\"e\">$f<v>${esc(v.code)}</v></c>"
                else -> "<c r=\"$ref\"$st>$f</c>"
            }
        }
        return when (cell.kind) {
            Cell.NUM -> "<c r=\"$ref\"$st><v>${esc(cell.text)}</v></c>"
            Cell.STR -> "<c r=\"$ref\"$st t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(cell.text)}</t></is></c>"
            Cell.BOOL -> "<c r=\"$ref\"$st t=\"b\"><v>${cell.text}</v></c>"
            Cell.ERR -> "<c r=\"$ref\"$st t=\"e\"><v>${esc(cell.text)}</v></c>"
            else -> "<c r=\"$ref\"$st/>"
        }
    }
}
