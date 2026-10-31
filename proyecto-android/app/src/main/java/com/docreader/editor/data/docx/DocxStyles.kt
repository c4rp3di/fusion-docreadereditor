package com.docreader.editor.data.docx

import android.graphics.Color
import com.docreader.editor.data.engine.OoxmlZip

class StyleInfo {
    var basedOn: String? = null
    var sizePt: Float? = null
    var bold: Boolean? = null
    var italic: Boolean? = null
    var color: Int? = null
    var before: Float? = null
    var after: Float? = null
    var line: Float? = null       // multiplicador (1.0 = sencillo)
    var jc: String? = null
    var left: Float? = null
    var hanging: Float? = null
    var firstLine: Float? = null
}

/** Estilos reales del documento (styles.xml): tamaños, negrita, espaciado, alineación... con herencia basedOn. */
class DocxStyles(
    private val map: Map<String, StyleInfo>,
    private val defaults: StyleInfo,
    private val normalId: String?
) {
    private fun merge(out: StyleInfo, s: StyleInfo) {
        s.sizePt?.let { out.sizePt = it }
        s.bold?.let { out.bold = it }
        s.italic?.let { out.italic = it }
        s.color?.let { out.color = it }
        s.before?.let { out.before = it }
        s.after?.let { out.after = it }
        s.line?.let { out.line = it }
        s.jc?.let { out.jc = it }
        s.left?.let { out.left = it }
        s.hanging?.let { out.hanging = it }
        s.firstLine?.let { out.firstLine = it }
    }

    fun resolve(id: String?): StyleInfo {
        val out = StyleInfo()
        merge(out, defaults)
        normalId?.let { n -> map[n]?.let { merge(out, it) } }
        if (id != null && id != normalId) {
            val chain = ArrayList<StyleInfo>()
            var cur: String? = id
            var n = 0
            while (cur != null && n++ < 10) {
                val s = map[cur] ?: break
                chain.add(s)
                cur = s.basedOn
            }
            for (s in chain.asReversed()) merge(out, s)
        }
        return out
    }

    companion object {
        val EMPTY = DocxStyles(emptyMap(), StyleInfo(), null)

        private fun on(tag: String): Boolean {
            val v = X.attr(tag, "val") ?: return true
            return v != "0" && v != "false" && v != "off"
        }

        private fun readProps(inner: String, s: StyleInfo) {
            var body = inner
            val pPr = Regex("<w:pPr>(.*?)</w:pPr>", RegexOption.DOT_MATCHES_ALL).find(body)
            if (pPr != null) {
                val p = pPr.groupValues[1]
                Regex("<w:spacing\\b[^>]*>").find(p)?.value?.let { sp ->
                    X.attr(sp, "before")?.toFloatOrNull()?.let { s.before = it / 20f }
                    X.attr(sp, "after")?.toFloatOrNull()?.let { s.after = it / 20f }
                    val rule = X.attr(sp, "lineRule")
                    if (rule == null || rule == "auto") X.attr(sp, "line")?.toFloatOrNull()?.let { s.line = it / 240f }
                }
                Regex("<w:jc\\b[^>]*>").find(p)?.value?.let { jc ->
                    s.jc = when (X.attr(jc, "val")) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "both"; else -> "left" }
                }
                Regex("<w:ind\\b[^>]*>").find(p)?.value?.let { ind ->
                    (X.attr(ind, "left") ?: X.attr(ind, "start"))?.toFloatOrNull()?.let { s.left = it / 20f }
                    X.attr(ind, "hanging")?.toFloatOrNull()?.let { s.hanging = it / 20f }
                    X.attr(ind, "firstLine")?.toFloatOrNull()?.let { s.firstLine = it / 20f }
                }
                body = body.replace(pPr.value, "")
            }
            val r = Regex("<w:rPr>(.*?)</w:rPr>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1) ?: return
            Regex("<w:sz\\b[^>]*>").find(r)?.value?.let { X.attr(it, "val")?.toFloatOrNull()?.let { v -> s.sizePt = v / 2f } }
            Regex("<w:b\\b[^>]*>").find(r)?.value?.let { s.bold = on(it) }
            Regex("<w:i\\b[^>]*>").find(r)?.value?.let { s.italic = on(it) }
            Regex("<w:color\\b[^>]*>").find(r)?.value?.let { c ->
                val v = X.attr(c, "val")
                if (v != null && v.length == 6) try { s.color = Color.parseColor("#$v") } catch (_: Exception) { }
            }
        }

        fun parse(xml: String?): DocxStyles {
            if (xml == null) return EMPTY
            val defaults = StyleInfo()
            Regex("<w:docDefaults>(.*?)</w:docDefaults>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let { d ->
                Regex("<w:rPrDefault>(.*?)</w:rPrDefault>", RegexOption.DOT_MATCHES_ALL).find(d)?.groupValues?.get(1)?.let { readProps(it, defaults) }
                Regex("<w:pPrDefault>(.*?)</w:pPrDefault>", RegexOption.DOT_MATCHES_ALL).find(d)?.groupValues?.get(1)?.let { readProps(it, defaults) }
            }
            val map = HashMap<String, StyleInfo>()
            var normal: String? = null
            for (m in Regex("<w:style\\b([^>]*)>(.*?)</w:style>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val attrs = m.groupValues[1]
                if (!attrs.contains("w:type=\"paragraph\"")) continue
                val id = Regex("w:styleId=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: continue
                if (attrs.contains("w:default=\"1\"")) normal = id
                val s = StyleInfo()
                val inner = m.groupValues[2]
                s.basedOn = Regex("<w:basedOn\\b[^>]*>").find(inner)?.value?.let { X.attr(it, "val") }
                readProps(inner, s)
                map[id] = s
            }
            return DocxStyles(map, defaults, normal)
        }
    }
}
