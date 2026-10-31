package com.docreader.editor.data.docx

import com.docreader.editor.data.engine.OoxmlZip

/** Utilidades XML ligeras (sin DOM) para trabajar sobre el texto original y no perder nada. */
object X {
    private val tagRe = Regex("<(/?)([A-Za-z_][\\w.-]*:[\\w.-]+)((?:\\s[^>]*?)?)(/?)>")

    private val tagAnyRe = Regex("<(/?)([A-Za-z_][\\w.:-]*)((?:\\s[^>]*?)?)(/?)>")

    /** Igual que children() pero admite etiquetas sin prefijo (<font>, <xf>...). */
    fun childrenU(inner: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var depth = 0
        var start = -1
        var name = ""
        for (m in tagAnyRe.findAll(inner)) {
            val closing = m.groupValues[1] == "/"
            val selfClose = m.groupValues[4] == "/"
            if (closing) {
                depth--
                if (depth <= 0) {
                    if (start >= 0) out.add(name to inner.substring(start, m.range.last + 1))
                    start = -1
                    depth = 0
                }
            } else if (selfClose) {
                if (depth == 0) out.add(m.groupValues[2] to m.value)
            } else {
                if (depth == 0) { start = m.range.first; name = m.groupValues[2] }
                depth++
            }
        }
        return out
    }

    fun local(n: String) = n.substringAfter(':')

    /** Hijos de primer nivel de un fragmento: (nombre con prefijo, xml). */
    fun children(inner: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var depth = 0
        var start = -1
        var name = ""
        for (m in tagRe.findAll(inner)) {
            val closing = m.groupValues[1] == "/"
            val selfClose = m.groupValues[4] == "/"
            if (closing) {
                depth--
                if (depth <= 0) {
                    if (start >= 0) out.add(name to inner.substring(start, m.range.last + 1))
                    start = -1
                    depth = 0
                }
            } else if (selfClose) {
                if (depth == 0) out.add(m.groupValues[2] to m.value)
            } else {
                if (depth == 0) { start = m.range.first; name = m.groupValues[2] }
                depth++
            }
        }
        return out
    }

    fun openTag(raw: String): String = raw.substring(0, raw.indexOf('>') + 1)

    fun inner(raw: String): String {
        val open = openTag(raw)
        if (open.endsWith("/>")) return ""
        val end = raw.lastIndexOf("</")
        return if (end < open.length) "" else raw.substring(open.length, end)
    }

    fun attrs(tag: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        Regex("([\\w:.-]+)=\"([^\"]*)\"").findAll(openTag(tag)).forEach { m[it.groupValues[1]] = it.groupValues[2] }
        return m
    }

    fun setAttrs(tag: String, name: String, updates: Map<String, String?>): String {
        val a = attrs(tag)
        for ((k, v) in updates) if (v == null) a.remove(k) else a[k] = v
        return "<$name" + a.entries.joinToString("") { " ${it.key}=\"${it.value}\"" } + "/>"
    }

    fun attr(tag: String, name: String): String? = OoxmlZip.attr(openTag(tag), name)

    val PPR_ORDER = listOf(
        "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr",
        "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap",
        "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd",
        "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc",
        "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle",
        "rPr", "sectPr", "pPrChange"
    )
    val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline",
        "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing",
        "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText",
        "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath"
    )

    fun sorted(order: List<String>, items: List<Pair<String, String>>): List<Pair<String, String>> =
        items.withIndex().sortedWith(
            compareBy({ val i = order.indexOf(local(it.value.first)); if (i < 0) 999 else i }, { it.index })
        ).map { it.value }
}
