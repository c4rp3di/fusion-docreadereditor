package com.docreader.editor.data.docx

import android.text.Spanned
import com.docreader.editor.data.engine.OoxmlZip

object DocxWriter {
    const val BULLET_ID = 91001
    const val DECIMAL_ABS = 91002
    const val DECIMAL_BASE = 91100

    private class Ctx {
        val images = ArrayList<ImageData>()
        val styles = HashSet<String>()
        val numIds = HashSet<Int>()
    }

    internal val styleDefs = mapOf(
        "Title" to "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:qFormat/><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"52\"/></w:rPr></w:style>",
        "Heading1" to "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"60\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr></w:style>",
        "Heading2" to "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"30\"/></w:rPr></w:style>",
        "Heading3" to "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"160\" w:after=\"40\"/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr></w:style>"
    )

    fun write(doc: DocxDoc): ByteArray {
        val ctx = Ctx()
        val sb = StringBuilder(doc.prefix)
        for (b in doc.blocks) sb.append(ser(b, ctx))
        if (doc.blocks.lastOrNull { !it.hidden } is TableBlock) sb.append("<w:p/>")
        sb.append(doc.trailer).append(doc.suffix)

        val replace = mutableMapOf("word/document.xml" to sb.toString().toByteArray(Charsets.UTF_8))
        val add = mutableMapOf<String, ByteArray>()
        val parts = OoxmlZip.read(doc.bytes) {
            it == "[Content_Types].xml" || it == "word/_rels/document.xml.rels" ||
                it == "word/styles.xml" || it == "word/numbering.xml"
        }
        var types = parts["[Content_Types].xml"]?.toString(Charsets.UTF_8)
        var rels = parts["word/_rels/document.xml.rels"]?.toString(Charsets.UTF_8)

        // Estilos de título que el documento no define
        val missing = ctx.styles.filter { styleDefs.containsKey(it) }
        parts["word/styles.xml"]?.toString(Charsets.UTF_8)?.let { st ->
            val toAdd = missing.filter { !st.contains("w:styleId=\"$it\"") }
            if (toAdd.isNotEmpty() && st.contains("</w:styles>")) {
                replace["word/styles.xml"] = st.replace("</w:styles>", toAdd.joinToString("") { styleDefs[it]!! } + "</w:styles>")
                    .toByteArray(Charsets.UTF_8)
            }
        }

        // Listas propias
        val usesBullet = BULLET_ID in ctx.numIds
        val decimals = ctx.numIds.filter { it >= DECIMAL_BASE }.sorted()
        if (usesBullet || decimals.isNotEmpty()) {
            val abs = StringBuilder()
            if (usesBullet) abs.append(abstractNum(BULLET_ID, true))
            if (decimals.isNotEmpty()) abs.append(abstractNum(DECIMAL_ABS, false))
            val nums = StringBuilder()
            if (usesBullet) nums.append("<w:num w:numId=\"$BULLET_ID\"><w:abstractNumId w:val=\"$BULLET_ID\"/></w:num>")
            for (id in decimals) {
                nums.append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"$DECIMAL_ABS\"/>")
                    .append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
            }
            val numXml = parts["word/numbering.xml"]?.toString(Charsets.UTF_8)
            if (numXml != null && numXml.contains("</w:numbering>")) {
                var x = numXml
                val firstNum = Regex("<w:num\\s").find(x)
                x = if (firstNum != null) x.replaceRange(firstNum.range.first, firstNum.range.first, abs.toString())
                else x.replace("</w:numbering>", abs.toString() + "</w:numbering>")
                x = x.replace("</w:numbering>", nums.toString() + "</w:numbering>")
                replace["word/numbering.xml"] = x.toByteArray(Charsets.UTF_8)
            } else {
                val x = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    abs + nums + "</w:numbering>"
                add["word/numbering.xml"] = x.toByteArray(Charsets.UTF_8)
                rels = rels?.replace(
                    "</Relationships>",
                    "<Relationship Id=\"rIdDRNum\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/></Relationships>"
                )
                types = types?.replace(
                    "</Types>",
                    "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>"
                )
            }
        }

        // Imágenes nuevas
        if (ctx.images.isNotEmpty()) {
            val relAdd = StringBuilder()
            for (img in ctx.images) {
                add["word/media/${img.fileName}"] = img.bytes
                relAdd.append("<Relationship Id=\"${img.relId}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${img.fileName}\"/>")
            }
            rels = rels?.replace("</Relationships>", relAdd.toString() + "</Relationships>")
            for (ext in ctx.images.map { it.ext }.toSet()) {
                val ct = if (ext == "png") "image/png" else "image/jpeg"
                if (types != null && !Regex("Extension=\"$ext\"", RegexOption.IGNORE_CASE).containsMatchIn(types!!)) {
                    types = types!!.replace("</Types>", "<Default Extension=\"$ext\" ContentType=\"$ct\"/></Types>")
                }
            }
        }
        types?.let { replace["[Content_Types].xml"] = it.toByteArray(Charsets.UTF_8) }
        rels?.let { replace["word/_rels/document.xml.rels"] = it.toByteArray(Charsets.UTF_8) }
        return OoxmlZip.rewrite(doc.bytes, replace, emptySet(), add)
    }

    private fun abstractNum(id: Int, bullet: Boolean): String {
        val sb = StringBuilder("<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (i in 0..3) {
            val txt = if (bullet) (if (i % 2 == 0) "•" else "◦") else "%${i + 1}."
            sb.append("<w:lvl w:ilvl=\"$i\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${if (bullet) "bullet" else "decimal"}\"/>")
                .append("<w:lvlText w:val=\"$txt\"/><w:lvlJc w:val=\"left\"/>")
                .append("<w:pPr><w:ind w:left=\"${720 * (i + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        }
        return sb.append("</w:abstractNum>").toString()
    }

    private fun ser(b: Block, ctx: Ctx): String = when (b) {
        is ParaBlock -> if (!b.dirty && b.raw != null) b.raw else buildPara(b, ctx)
        is RawBlock -> b.xml
        is ImageBlock -> { ctx.images.add(b.image); imageXml(b.image) }
        is TableBlock -> if (!b.dirty && b.raw != null) b.raw else buildTable(b, ctx)
    }

    private fun buildPara(p: ParaBlock, ctx: Ctx): String {
        val open = p.openTag?.takeIf { !it.endsWith("/>") } ?: "<w:p>"
        return open + buildPPr(p.props, ctx) + buildRuns(p.text) + "</w:p>"
    }

    private fun buildPPr(p: PProps, ctx: Ctx): String {
        val kids = ArrayList<Pair<String, String>>(p.others)
        p.style?.let { kids.add("w:pStyle" to "<w:pStyle w:val=\"$it\"/>"); ctx.styles.add(it) }
        val nid = p.numId
        if (nid != null && nid > 0) {
            kids.add("w:numPr" to "<w:numPr><w:ilvl w:val=\"${p.ilvl}\"/><w:numId w:val=\"$nid\"/></w:numPr>")
            ctx.numIds.add(nid)
        }
        p.jc?.let { kids.add("w:jc" to "<w:jc w:val=\"$it\"/>") }
        if (p.lineChanged && p.line != null) {
            kids.add("w:spacing" to X.setAttrs(p.spacingXml ?: "<w:spacing/>", "w:spacing",
                mapOf("w:line" to p.line.toString(), "w:lineRule" to "auto")))
        } else p.spacingXml?.let { kids.add("w:spacing" to it) }
        if (p.indChanged && p.indLeft != null) {
            kids.add("w:ind" to X.setAttrs(p.indXml ?: "<w:ind/>", "w:ind",
                mapOf("w:left" to p.indLeft.toString(), "w:start" to null)))
        } else p.indXml?.let { kids.add("w:ind" to it) }
        if (kids.isEmpty()) return ""
        return "<w:pPr>" + X.sorted(X.PPR_ORDER, kids).joinToString("") { it.second } + "</w:pPr>"
    }

    private fun buildRuns(text: Spanned): String {
        val len = text.length
        if (len == 0) return ""
        val spans = text.getSpans(0, len, Any::class.java)
            .filter { (Fmt.kind(it) != 0 || it is ExtraRPr) && text.getSpanStart(it) < text.getSpanEnd(it) }
        val cuts = java.util.TreeSet<Int>()
        cuts.add(0); cuts.add(len)
        for (s in spans) { cuts.add(text.getSpanStart(s)); cuts.add(text.getSpanEnd(s)) }
        val pts = cuts.toList()
        val out = StringBuilder()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (a >= b) continue
            val cover = spans.filter { text.getSpanStart(it) <= a && text.getSpanEnd(it) >= b }
            val content = StringBuilder()
            val buf = StringBuilder()
            fun flush() {
                if (buf.isNotEmpty()) {
                    content.append("<w:t xml:space=\"preserve\">").append(OoxmlZip.escape(buf.toString())).append("</w:t>")
                    buf.clear()
                }
            }
            for (ch in text.subSequence(a, b)) when (ch) {
                '\n' -> { flush(); content.append("<w:br/>") }
                '\t' -> { flush(); content.append("<w:tab/>") }
                '\r' -> Unit
                else -> buf.append(ch)
            }
            flush()
            if (content.isEmpty()) continue
            out.append("<w:r>").append(rPrFor(cover)).append(content).append("</w:r>")
        }
        return out.toString()
    }

    private fun rPrFor(cover: List<Any>): String {
        val kids = ArrayList<Pair<String, String>>()
        var bold = false; var italic = false; var under = false; var strike = false
        var size: SizeSpan? = null; var color: FgSpan? = null; var hl: BgSpan? = null; var font: FontSpan? = null
        var extra: ExtraRPr? = null
        for (sp in cover) when (sp) {
            is BoldSpan -> bold = true
            is ItalicSpan -> italic = true
            is UlSpan -> under = true
            is StrSpan -> strike = true
            is SizeSpan -> size = sp
            is FgSpan -> color = sp
            is BgSpan -> hl = sp
            is FontSpan -> font = sp
            is ExtraRPr -> if (extra == null) extra = sp
        }
        font?.let {
            val n = OoxmlZip.escape(it.name).replace("\"", "&quot;")
            kids.add("w:rFonts" to "<w:rFonts w:ascii=\"$n\" w:hAnsi=\"$n\" w:cs=\"$n\"/>")
        }
        if (bold) kids.add("w:b" to "<w:b/>")
        if (italic) kids.add("w:i" to "<w:i/>")
        if (strike) kids.add("w:strike" to "<w:strike/>")
        color?.let { kids.add("w:color" to "<w:color w:val=\"${String.format("%06X", it.foregroundColor and 0xFFFFFF)}\"/>") }
        size?.let { kids.add("w:sz" to "<w:sz w:val=\"${Math.round(it.pt * 2)}\"/>") }
        hl?.let { kids.add("w:highlight" to "<w:highlight w:val=\"${Hl.name(it.backgroundColor)}\"/>") }
        if (under) kids.add("w:u" to "<w:u w:val=\"single\"/>")
        extra?.let { kids.addAll(it.children) }
        if (kids.isEmpty()) return ""
        return "<w:rPr>" + X.sorted(X.RPR_ORDER, kids).joinToString("") { it.second } + "</w:rPr>"
    }

    private fun buildTable(t: TableBlock, ctx: Ctx): String {
        val sb = StringBuilder("<w:tbl>").append(t.tblPr).append("<w:tblGrid>")
        for (w in t.grid) sb.append("<w:gridCol w:w=\"$w\"/>")
        sb.append("</w:tblGrid>")
        for (r in t.rows) {
            sb.append("<w:tr>").append(r.trPr)
            for (c in r.cells) {
                if (!c.dirty && c.raw != null) { sb.append(c.raw); continue }
                sb.append("<w:tc>").append(c.tcPr)
                val lines = c.text.split('\n')
                var off = 0
                for (line in lines) {
                    val sub = c.text.subSequence(off, off + line.length) as Spanned
                    sb.append("<w:p>").append(buildPPr(c.props, ctx)).append(buildRuns(sub)).append("</w:p>")
                    off += line.length + 1
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        return sb.append("</w:tbl>").toString()
    }

    private fun imageXml(img: ImageData): String {
        val n = 1000 + img.id
        return "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"${img.cx}\" cy=\"${img.cy}\"/><wp:docPr id=\"$n\" name=\"Imagen $n\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"${img.fileName}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"${img.relId}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${img.cx}\" cy=\"${img.cy}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }
}
