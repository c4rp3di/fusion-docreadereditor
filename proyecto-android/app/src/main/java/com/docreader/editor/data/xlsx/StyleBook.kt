package com.docreader.editor.data.xlsx

import com.docreader.editor.data.docx.X

class CellStyle(
    val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean,
    val size: Float, val color: Int?, val fill: Int?, val alignH: String?, val numFmt: String, val border: Boolean
)

/** Cambio de formato a aplicar sobre una celda. Los null significan "no tocar". */
class Mod(
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
    val strike: Boolean? = null, val size: Float? = null,
    val color: Int? = null, val clearColor: Boolean = false,
    val fill: Int? = null, val clearFill: Boolean = false,
    val alignH: String? = null, val numFmt: String? = null, val border: Boolean? = null
) {
    fun key() = listOf(bold, italic, underline, strike, size, color, clearColor, fill, clearFill, alignH, numFmt, border).joinToString("|")
    fun touchesFont() = bold != null || italic != null || underline != null || strike != null ||
        size != null || color != null || clearColor
}

class StyleBook(xml: String?, val existed: Boolean) {
    private val src: String = xml ?: DEFAULT_XML
    private val numFmts = LinkedHashMap<Int, String>()
    private val fonts = ArrayList<String>()
    private val fills = ArrayList<String>()
    private val borders = ArrayList<String>()
    private val xfs = ArrayList<String>()
    var dirty = false
    private val resolved = HashMap<Int, CellStyle>()
    private val derived = HashMap<String, Int>()

    init {
        sectionItems("numFmts").forEach { (_, x) ->
            val a = X.attrs(x)
            val id = a["numFmtId"]?.toIntOrNull()
            val code = a["formatCode"]
            if (id != null && code != null) numFmts[id] = unesc(code)
        }
        fonts.addAll(sectionItems("fonts").map { it.second })
        fills.addAll(sectionItems("fills").map { it.second })
        borders.addAll(sectionItems("borders").map { it.second })
        xfs.addAll(sectionItems("cellXfs").map { it.second })
        if (xfs.isEmpty()) xfs.add("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        if (fonts.isEmpty()) fonts.add("<font><sz val=\"11\"/><name val=\"Calibri\"/></font>")
    }

    private fun sectionItems(tag: String): List<Pair<String, String>> {
        val m = Regex("<$tag\\b([^>]*)>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(src) ?: return emptyList()
        return X.childrenU(m.groupValues[2])
    }

    private fun unesc(s: String) = s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun colorOf(tag: String): Int? {
        val a = X.attrs(tag)
        a["rgb"]?.let { v ->
            val n = v.toLongOrNull(16) ?: return null
            return (n.toInt() or 0xFF000000.toInt())
        }
        a["theme"]?.toIntOrNull()?.let { return THEME.getOrNull(it) }
        a["indexed"]?.toIntOrNull()?.let { return INDEXED[it] }
        return null
    }

    fun style(idx: Int): CellStyle = resolved.getOrPut(idx) { build(idx) }

    private fun build(idx: Int): CellStyle {
        val xf = xfs.getOrNull(idx) ?: xfs[0]
        val a = X.attrs(xf)
        val nf = a["numFmtId"]?.toIntOrNull() ?: 0
        val code = numFmts[nf] ?: NumFmt.builtin[nf] ?: "General"
        var bold = false; var italic = false; var under = false; var strike = false
        var size = 11f; var color: Int? = null
        val font = fonts.getOrNull(a["fontId"]?.toIntOrNull() ?: 0)
        if (font != null) for ((n, x) in X.childrenU(X.inner(font))) {
            val on = X.attrs(x)["val"].let { it == null || (it != "0" && it != "false") }
            when (n) {
                "b" -> bold = on
                "i" -> italic = on
                "strike" -> strike = on
                "u" -> under = X.attrs(x)["val"] != "none"
                "sz" -> size = X.attrs(x)["val"]?.toFloatOrNull() ?: 11f
                "color" -> color = colorOf(x)
            }
        }
        var fill: Int? = null
        val fx = fills.getOrNull(a["fillId"]?.toIntOrNull() ?: 0)
        if (fx != null && fx.contains("patternType=\"solid\"")) {
            Regex("<fgColor\\b[^>]*?/?>").find(fx)?.let { fill = colorOf(it.value) }
        }
        val bx = borders.getOrNull(a["borderId"]?.toIntOrNull() ?: 0)
        val border = bx != null && Regex("<(left|right|top|bottom)\\b[^>]*style=\"[^\"]+\"").containsMatchIn(bx)
        val al = Regex("<alignment\\b([^>]*?)/?>").find(xf)?.let { X.attrs(it.value)["horizontal"] }
        return CellStyle(bold, italic, under, strike, size, color, fill, al, code, border)
    }

    private fun hex8(c: Int) = String.format("%08X", c)

    fun derive(base: Int, m: Mod): Int {
        val key = "$base#${m.key()}"
        derived[key]?.let { return it }
        val xf = xfs.getOrNull(base) ?: xfs[0]
        val a = X.attrs(xf)
        var fontId = a["fontId"]?.toIntOrNull() ?: 0
        var fillId = a["fillId"]?.toIntOrNull() ?: 0
        var borderId = a["borderId"]?.toIntOrNull() ?: 0
        var nfId = a["numFmtId"]?.toIntOrNull() ?: 0
        if (m.touchesFont()) fontId = indexOf(fonts, newFont(fonts.getOrNull(fontId) ?: "<font/>", m))
        if (m.fill != null) fillId = indexOf(fills,
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${hex8(m.fill)}\"/><bgColor indexed=\"64\"/></patternFill></fill>")
        else if (m.clearFill) fillId = 0
        if (m.border != null) borderId = if (m.border) indexOf(borders,
            "<border>" + listOf("left", "right", "top", "bottom").joinToString("") { "<$it style=\"thin\"><color auto=\"1\"/></$it>" } + "<diagonal/></border>") else 0
        if (m.numFmt != null) nfId = numFmtId(m.numFmt)
        val al = Regex("<alignment\\b([^>]*?)/?>").find(xf)
        val alAttrs = LinkedHashMap<String, String>()
        if (al != null) alAttrs.putAll(X.attrs(al.value))
        m.alignH?.let { if (it == "general") alAttrs.remove("horizontal") else alAttrs["horizontal"] = it }
        val sb = StringBuilder("<xf numFmtId=\"$nfId\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"$borderId\"")
        sb.append(" xfId=\"${a["xfId"] ?: "0"}\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"")
        if (alAttrs.isNotEmpty()) {
            sb.append(" applyAlignment=\"1\"><alignment")
            for ((k, v) in alAttrs) sb.append(" $k=\"$v\"")
            sb.append("/></xf>")
        } else sb.append("/>")
        val idx = indexOf(xfs, sb.toString())
        derived[key] = idx
        return idx
    }

    private fun indexOf(list: ArrayList<String>, item: String): Int {
        val i = list.indexOf(item)
        if (i >= 0) return i
        list.add(item)
        dirty = true
        return list.size - 1
    }

    private fun numFmtId(code: String): Int {
        NumFmt.builtin.entries.firstOrNull { it.value == code }?.let { return it.key }
        numFmts.entries.firstOrNull { it.value == code }?.let { return it.key }
        val id = maxOf(164, (numFmts.keys.maxOrNull() ?: 163) + 1)
        numFmts[id] = code
        dirty = true
        return id
    }

    private fun newFont(base: String, m: Mod): String {
        val kids = X.childrenU(X.inner(base)).toMutableList()
        fun drop(n: String) = kids.removeAll { it.first == n }
        m.bold?.let { drop("b"); if (it) kids.add("b" to "<b/>") }
        m.italic?.let { drop("i"); if (it) kids.add("i" to "<i/>") }
        m.underline?.let { drop("u"); if (it) kids.add("u" to "<u/>") }
        m.strike?.let { drop("strike"); if (it) kids.add("strike" to "<strike/>") }
        m.size?.let {
            drop("sz")
            kids.add("sz" to "<sz val=\"${if (it == Math.rint(it.toDouble()).toFloat()) it.toInt().toString() else it.toString()}\"/>")
        }
        if (m.clearColor) drop("color") else m.color?.let { drop("color"); kids.add("color" to "<color rgb=\"${hex8(it)}\"/>") }
        val order = listOf("b", "i", "strike", "condense", "extend", "outline", "shadow", "u", "vertAlign", "sz", "color", "name", "family", "charset", "scheme")
        val sorted = kids.withIndex().sortedWith(compareBy({ val i = order.indexOf(it.value.first); if (i < 0) 99 else i }, { it.index }))
        return "<font>" + sorted.joinToString("") { it.value.second } + "</font>"
    }

    private fun replaceSection(x: String, tag: String, items: List<String>): String {
        val m = Regex("<$tag\\b([^>]*)>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(x) ?: return x
        val attrs = m.groupValues[1].replace(Regex("\\scount=\"\\d+\""), "")
        return x.replaceRange(m.range, "<$tag count=\"${items.size}\"$attrs>${items.joinToString("")}</$tag>")
    }

    fun toXml(): String {
        var x = src
        x = replaceSection(x, "fonts", fonts)
        x = replaceSection(x, "fills", fills)
        x = replaceSection(x, "borders", borders)
        x = replaceSection(x, "cellXfs", xfs)
        if (numFmts.isNotEmpty()) {
            val items = numFmts.map { "<numFmt numFmtId=\"${it.key}\" formatCode=\"${esc(it.value)}\"/>" }
            if (Regex("<numFmts\\b").containsMatchIn(x)) x = replaceSection(x, "numFmts", items)
            else {
                val open = Regex("<styleSheet\\b[^>]*>").find(x)
                if (open != null) x = x.replaceRange(open.range.last + 1, open.range.last + 1,
                    "<numFmts count=\"${items.size}\">${items.joinToString("")}</numFmts>")
            }
        }
        return x
    }

    companion object {
        val THEME = listOf(0xFFFFFFFF, 0xFF000000, 0xFFE7E6E6, 0xFF44546A, 0xFF4472C4, 0xFFED7D31,
            0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47).map { it.toInt() }
        val INDEXED = mapOf(8 to 0xFF000000.toInt(), 9 to 0xFFFFFFFF.toInt(), 10 to 0xFFFF0000.toInt(),
            11 to 0xFF00FF00.toInt(), 12 to 0xFF0000FF.toInt(), 13 to 0xFFFFFF00.toInt(),
            14 to 0xFFFF00FF.toInt(), 15 to 0xFF00FFFF.toInt())
        const val DEFAULT_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "</styleSheet>"
    }
}
