package com.docreader.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import com.docreader.editor.data.docx.BoldSpan
import com.docreader.editor.data.docx.Block
import com.docreader.editor.data.docx.BgSpan
import com.docreader.editor.data.docx.DocxDoc
import com.docreader.editor.data.docx.DocxWriter
import com.docreader.editor.data.docx.FgSpan
import com.docreader.editor.data.docx.Fmt
import com.docreader.editor.data.docx.FontSpan
import com.docreader.editor.data.docx.ImageBlock
import com.docreader.editor.data.docx.ItalicSpan
import com.docreader.editor.data.docx.ParaBlock
import com.docreader.editor.data.docx.RawBlock
import com.docreader.editor.data.docx.SizeSpan
import com.docreader.editor.data.docx.StrSpan
import com.docreader.editor.data.docx.TableBlock
import com.docreader.editor.data.docx.UlSpan
import com.docreader.editor.data.docx.X
import com.docreader.editor.data.engine.OoxmlZip
import kotlin.math.max
import kotlin.math.min

private class PtSpan(val pt: Float) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.textSize = pt }
    override fun updateMeasureState(tp: TextPaint) { tp.textSize = pt }
}

/**
 * Reparte el documento en páginas (A4/Carta según el documento) para mostrar cómo quedaría impreso.
 * Es una aproximación: las fuentes del teléfono no son las de Word, así que los saltos pueden diferir.
 */
class DocxPaginator(private val doc: DocxDoc, private val blocks: List<Block>) {
    private class Geo(val w: Float, val h: Float, val ml: Float, val mr: Float, val mt: Float, val mb: Float)

    private abstract class Op { abstract fun draw(c: Canvas) }

    private class ParaOp(
        val layout: StaticLayout, val first: Int, val last: Int, val y: Float,
        val marker: String?, val markerX: Float
    ) : Op() {
        override fun draw(c: Canvas) {
            val top = layout.getLineTop(first).toFloat()
            val bottom = layout.getLineBottom(last).toFloat()
            c.save()
            c.translate(0f, y - top)
            c.clipRect(0f, top, layout.width.toFloat(), bottom)
            layout.draw(c)
            c.restore()
            if (marker != null) c.drawText(marker, markerX, y - top + layout.getLineBaseline(first), layout.paint)
        }
    }

    private class ImgOp(val bmp: Bitmap?, val x: Float, val y: Float, val w: Float, val h: Float) : Op() {
        override fun draw(c: Canvas) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            if (bmp != null) c.drawBitmap(bmp, null, android.graphics.RectF(x, y, x + w, y + h), p)
            else {
                p.color = 0xFFE0E0E0.toInt()
                c.drawRect(x, y, x + w, y + h, p)
            }
        }
    }

    private class CellDraw(val x: Float, val w: Float, val layout: StaticLayout)
    private class RowDraw(val h: Float, val cells: List<CellDraw>)

    private class TableOp(val y0: Float, val rows: List<RowDraw>) : Op() {
        override fun draw(c: Canvas) {
            val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0xFF555555.toInt() }
            var y = y0
            for (r in rows) {
                for (cell in r.cells) {
                    c.drawRect(cell.x, y, cell.x + cell.w, y + r.h, line)
                    c.save()
                    c.translate(cell.x + 4f, y + 3f)
                    cell.layout.draw(c)
                    c.restore()
                }
                y += r.h
            }
        }
    }

    private val g = geo()
    private var rels: Map<String, String>? = null
    private var media: Map<String, ByteArray>? = null

    private fun geo(): Geo {
        val t = doc.trailer
        fun a(tag: String, name: String): Float? =
            Regex("<w:$tag\\b[^>]*?w:$name=\"(-?\\d+)\"").find(t)?.groupValues?.get(1)?.toFloatOrNull()
        val w = a("pgSz", "w") ?: 11906f
        val h = a("pgSz", "h") ?: 16838f
        val top = max(0f, a("pgMar", "top") ?: 1417f)
        val bottom = max(0f, a("pgMar", "bottom") ?: 1417f)
        val left = max(0f, a("pgMar", "left") ?: 1417f)
        val right = max(0f, a("pgMar", "right") ?: 1417f)
        return Geo(w / 20f, h / 20f, left / 20f, right / 20f, top / 20f, bottom / 20f)
    }

    private fun twips(xml: String?, name: String): Float? = xml?.let { X.attr(it, name)?.toFloatOrNull()?.div(20f) }

    private fun layoutText(
        text: CharSequence, width: Float, size: Float, bold: Boolean, jc: String?, mult: Float,
        first: Int, rest: Int, color: Int = Color.BLACK, italic: Boolean = false
    ): StaticLayout {
        val sb = SpannableStringBuilder(text.toString())
        if (text is Spanned) {
            for (s in text.getSpans(0, text.length, Any::class.java)) {
                val st = text.getSpanStart(s)
                val en = text.getSpanEnd(s)
                if (st >= en) continue
                when (s) {
                    is SizeSpan -> sb.setSpan(PtSpan(s.pt), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    is BoldSpan, is ItalicSpan, is UlSpan, is StrSpan, is FgSpan, is BgSpan, is FontSpan ->
                        sb.setSpan(Fmt.clone(s), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        if (sb.isNotEmpty() && (first != 0 || rest != 0)) {
            sb.setSpan(LeadingMarginSpan.Standard(first.coerceAtLeast(0), rest.coerceAtLeast(0)), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = size
        tp.color = color
        tp.isSubpixelText = true
        if (bold || italic) tp.typeface = Typeface.create(
            Typeface.DEFAULT, if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else Typeface.ITALIC
        )
        val b = StaticLayout.Builder.obtain(sb, 0, sb.length, tp, max(10, width.toInt()))
            .setAlignment(when (jc) { "center" -> Layout.Alignment.ALIGN_CENTER; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL })
            .setLineSpacing(0f, mult)
            .setIncludePad(false)
        if (jc == "both" && Build.VERSION.SDK_INT >= 26) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    private fun fmtOf(id: Int, ilvl: Int): String = when {
        id == DocxWriter.BULLET_ID -> "bullet"
        id == DocxWriter.DECIMAL_ABS || id >= DocxWriter.DECIMAL_BASE -> "decimal"
        else -> doc.numbering.fmt(id, ilvl)
    }

    private fun numText(n: Int, fmt: String): String {
        fun letters(x0: Int): String { var x = x0; val sb = StringBuilder(); while (x > 0) { x--; sb.append('A' + x % 26); x /= 26 }; return sb.reverse().toString() }
        return when (fmt) { "lowerLetter" -> letters(n).lowercase() + "."; "upperLetter" -> letters(n) + "."; else -> "$n." }
    }

    private class HF(val lines: List<String>, val align: String)

    private fun ensureRels() {
        if (rels != null) return
        val r = OoxmlZip.read(doc.bytes) { it == "word/_rels/document.xml.rels" }["word/_rels/document.xml.rels"]
            ?.toString(Charsets.UTF_8) ?: ""
        rels = Regex("<Relationship\\s[^>]*?/>").findAll(r).associate {
            (OoxmlZip.attr(it.value, "Id") ?: "") to (OoxmlZip.attr(it.value, "Target") ?: "")
        }
    }

    /** Texto del encabezado/pie por defecto ({PAGE} y {PAGES} se sustituyen en cada página). */
    private fun loadHF(kind: String): HF? {
        val refs = Regex("<w:${kind}Reference\\b[^>]*>").findAll(doc.trailer).map { it.value }.toList()
        val def = refs.firstOrNull { it.contains("w:type=\"default\"") } ?: refs.firstOrNull() ?: return null
        val rid = Regex("r:id=\"([^\"]+)\"").find(def)?.groupValues?.get(1) ?: return null
        ensureRels()
        val target = rels?.get(rid) ?: return null
        val path = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
        val xml = OoxmlZip.read(doc.bytes) { it == path }[path]?.toString(Charsets.UTF_8) ?: return null
        val tokens = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|<w:instrText[^>]*>([^<]*)</w:instrText>|<w:fldSimple\\b[^>]*w:instr=\"([^\"]*)\"")
        val lines = ArrayList<String>()
        for (pm in Regex("<w:p\\b[^>]*>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val sb = StringBuilder()
            for (t in tokens.findAll(pm.groupValues[1])) {
                val txt = t.groupValues[1]
                val instr = t.groupValues[2].ifEmpty { t.groupValues[3] }
                when {
                    t.value.startsWith("<w:t") && !t.value.startsWith("<w:instr") && txt.isNotEmpty() -> sb.append(OoxmlZip.unescape(txt))
                    instr.contains("NUMPAGES") -> sb.append("{PAGES}")
                    instr.contains("PAGE") -> sb.append("{PAGE}")
                }
            }
            val line = sb.toString().replace(Regex("\\{PAGE\\}\\d+"), "{PAGE}").replace(Regex("\\{PAGES\\}\\d+"), "{PAGES}").trim()
            if (line.isNotEmpty()) lines.add(line)
        }
        if (lines.isEmpty()) return null
        val jc = Regex("<w:jc\\b[^>]*w:val=\"(\\w+)\"").find(xml)?.groupValues?.get(1)
        return HF(lines, when (jc) { "center" -> "center"; "right", "end" -> "right"; else -> "left" })
    }

    private fun loadMedia(xml: String): Bitmap? {
        val rid = Regex("r:embed=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: return null
        ensureRels()
        val target = rels?.get(rid) ?: return null
        val path = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
        if (media == null) {
            media = OoxmlZip.read(doc.bytes) { it.startsWith("word/media/") }.filter { it.value.size < 8_000_000 }
        }
        return decode(media?.get(path))
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > 1000) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun paginate(): List<PreviewPage> {
        val cw = g.w - g.ml - g.mr
        val ch = g.h - g.mt - g.mb
        var y = 0f
        var cur = ArrayList<Op>()
        val all = ArrayList<List<Op>>()
        fun newPage() { all.add(cur); cur = ArrayList(); y = 0f }

        fun placeBox(w0: Float, h0: Float): Triple<Float, Float, Float> {
            var w = min(w0, cw)
            var h = h0 * (w / w0)
            if (h > ch) { w *= ch / h; h = ch }
            if (y + h > ch && y > 0f) newPage()
            return Triple(0f, w, h)
        }

        var lastId: Int? = null
        val counters = IntArray(10)

        for (b in blocks) {
            when (b) {
                is ParaBlock -> {
                    val p = b.props
                    if (p.others.any { it.first.endsWith("pageBreakBefore") } && cur.isNotEmpty()) newPage()
                    val lvl = p.headingLevel()
                    val st = doc.styles.resolve(p.style)
                    val base = st.sizePt ?: when (lvl) { 0 -> 26f; 1 -> 16f; 2 -> 13f; 3 -> 12f; else -> 11f }
                    val before = twips(p.spacingXml, "before") ?: st.before ?: if (lvl >= 0) 12f else 0f
                    val after = twips(p.spacingXml, "after") ?: st.after ?: if (lvl >= 0) 4f else 8f
                    val lineAttr = p.spacingXml?.let { X.attr(it, "line")?.toFloatOrNull() }
                    val rule = p.spacingXml?.let { X.attr(it, "lineRule") }
                    val mult = when {
                        p.lineChanged && p.line != null -> p.line!! / 240f
                        lineAttr != null && (rule == null || rule == "auto") -> lineAttr / 240f
                        else -> st.line ?: 1.08f
                    }
                    val nid = p.numId
                    var marker: String? = null
                    var left: Float
                    var first: Float
                    if (nid != null) {
                        val fmt = fmtOf(nid, p.ilvl)
                        if (lastId != nid) counters.fill(0)
                        counters[p.ilvl.coerceIn(0, 9)]++
                        for (k in p.ilvl + 1 until 10) counters[k] = 0
                        marker = if (fmt == "bullet") (if (p.ilvl % 2 == 0) "•" else "◦") else numText(counters[p.ilvl.coerceIn(0, 9)], fmt)
                        lastId = nid
                        left = 18f * (p.ilvl + 1)
                        first = left
                    } else {
                        lastId = null
                        left = (if (p.indChanged) p.indLeft?.div(20f) else twips(p.indXml, "left") ?: twips(p.indXml, "start")) ?: st.left ?: 0f
                        val hanging = twips(p.indXml, "hanging") ?: st.hanging ?: 0f
                        val firstLine = twips(p.indXml, "firstLine") ?: st.firstLine ?: 0f
                        first = left + firstLine - hanging
                    }
                    val layout = layoutText(b.text, cw, base, st.bold ?: (lvl >= 0), p.jc ?: st.jc, mult, first.toInt(), left.toInt(), st.color ?: Color.BLACK, st.italic == true)
                    if (y > 0f) y += before
                    var line = 0
                    val lc = layout.lineCount
                    while (line < lc) {
                        val remaining = ch - y
                        val topY = layout.getLineTop(line)
                        var last = line - 1
                        for (k in line until lc) { if (layout.getLineBottom(k) - topY <= remaining) last = k else break }
                        if (last < line) { if (y > 0f) { newPage(); continue } else last = line }
                        cur.add(ParaOp(layout, line, last, y, if (line == 0) marker else null, left - 16f))
                        y += layout.getLineBottom(last) - topY
                        line = last + 1
                        if (line < lc) newPage()
                    }
                    y += after
                }
                is TableBlock -> {
                    val nCols = b.rows.maxOfOrNull { r -> r.cells.sumOf { c -> spanOf(c.tcPr) } } ?: 1
                    var base = b.grid.map { it / 20f }
                    if (base.size != nCols) base = List(nCols) { cw / nCols }
                    val total = base.sum()
                    val f = if (total > cw) cw / total else 1f
                    val colW = base.map { it * f }
                    var chunk = ArrayList<RowDraw>()
                    var y0 = y
                    for (r in b.rows) {
                        var x = 0f
                        var gi = 0
                        val cells = ArrayList<CellDraw>()
                        var rowH = 14f
                        for (c in r.cells) {
                            val span = spanOf(c.tcPr)
                            val w = (gi until min(gi + span, colW.size)).sumOf { colW[it].toDouble() }.toFloat()
                            val lay = layoutText(c.text, w - 8f, 11f, false, c.props.jc, 1.0f, 0, 0)
                            cells.add(CellDraw(x, w, lay))
                            rowH = max(rowH, lay.height + 6f)
                            x += w; gi += span
                        }
                        if (y + rowH > ch && y > 0f) {
                            if (chunk.isNotEmpty()) cur.add(TableOp(y0, chunk))
                            chunk = ArrayList()
                            newPage()
                            y0 = 0f
                        }
                        chunk.add(RowDraw(rowH, cells))
                        y += rowH
                    }
                    if (chunk.isNotEmpty()) cur.add(TableOp(y0, chunk))
                    y += 6f
                    lastId = null
                }
                is ImageBlock -> {
                    val bmp = decode(b.image.bytes)
                    val (x, w, h) = placeBox(b.image.cx / 12700f, b.image.cy / 12700f)
                    cur.add(ImgOp(bmp, x, y, w, h))
                    y += h + 6f
                    lastId = null
                }
                is RawBlock -> if (!b.hidden) {
                    val bmp = loadMedia(b.xml)
                    val ext = Regex("<wp:extent\\s+cx=\"(\\d+)\"\\s+cy=\"(\\d+)\"").find(b.xml)
                    if (bmp != null) {
                        val w0 = ext?.groupValues?.get(1)?.toFloatOrNull()?.div(12700f) ?: bmp.width * 0.75f
                        val h0 = ext?.groupValues?.get(2)?.toFloatOrNull()?.div(12700f) ?: bmp.height * 0.75f
                        val (x, w, h) = placeBox(max(1f, w0), max(1f, h0))
                        cur.add(ImgOp(bmp, x, y, w, h))
                        y += h + 6f
                    } else if (b.label.isNotBlank()) {
                        val lay = layoutText(b.label, cw, 10f, false, null, 1.0f, 0, 0, 0xFF777777.toInt())
                        if (y + lay.height > ch && y > 0f) newPage()
                        cur.add(ParaOp(lay, 0, lay.lineCount - 1, y, null, 0f))
                        y += lay.height + 6f
                    }
                    lastId = null
                    if (b.xml.contains("w:type=\"page\"")) newPage()
                }
            }
        }
        all.add(cur)
        val total = all.size
        val header = try { loadHF("header") } catch (e: Exception) { null }
        val footer = try { loadHF("footer") } catch (e: Exception) { null }
        val hDist = Regex("<w:pgMar\\b[^>]*?w:header=\"(\\d+)\"").find(doc.trailer)?.groupValues?.get(1)?.toFloatOrNull()?.div(20f) ?: 35f
        val fDist = Regex("<w:pgMar\\b[^>]*?w:footer=\"(\\d+)\"").find(doc.trailer)?.groupValues?.get(1)?.toFloatOrNull()?.div(20f) ?: 35f
        return all.mapIndexed { i, ops ->
            PreviewPage(g.w, g.h) { c ->
                c.save()
                c.translate(g.ml, g.mt)
                for (op in ops) op.draw(c)
                c.restore()
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444444.toInt(); textSize = 10f }
                fun lines(hf: HF, startY: Float) {
                    var y = startY
                    for (raw in hf.lines) {
                        val t = raw.replace("{PAGE}", "${i + 1}").replace("{PAGES}", "$total")
                        val w = p.measureText(t)
                        val x = when (hf.align) { "center" -> (g.w - w) / 2f; "right" -> g.w - g.mr - w; else -> g.ml }
                        c.drawText(t, x, y, p)
                        y += 12f
                    }
                }
                if (header != null) lines(header, hDist + 10f)
                if (footer != null) lines(footer, g.h - fDist - 12f * (footer.lines.size - 1))
                else {
                    val pn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 9f; textAlign = Paint.Align.CENTER }
                    c.drawText("${i + 1} / $total", g.w / 2f, g.h - max(12f, g.mb / 2f) + 3f, pn)
                }
            }
        }
    }

    private fun spanOf(tcPr: String): Int =
        Regex("<w:gridSpan\\s[^>]*w:val=\"(\\d+)\"").find(tcPr)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
}
