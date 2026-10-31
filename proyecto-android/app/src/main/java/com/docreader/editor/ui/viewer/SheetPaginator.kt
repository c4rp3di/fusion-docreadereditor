package com.docreader.editor.ui.viewer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.docreader.editor.data.docx.X
import com.docreader.editor.data.xlsx.Cell
import com.docreader.editor.data.xlsx.CellStyle
import com.docreader.editor.data.xlsx.SheetData
import com.docreader.editor.data.xlsx.XlsxDoc
import com.docreader.editor.data.xlsx.colOf
import com.docreader.editor.data.xlsx.rowOf
import kotlin.math.max
import kotlin.math.min

/** Vista de impresión de una hoja: cómo se repartiría en páginas según la configuración de página del archivo. */
object SheetPaginator {
    private class CD(val x: Float, val y: Float, val w: Float, val h: Float, val r: Int, val c: Int, val text: String, val style: CellStyle?, val right: Boolean)

    fun paginate(doc: XlsxDoc, sh: SheetData): List<PreviewPage> {
        val tail = sh.tailBefore + sh.tailAfter
        val setup = Regex("<pageSetup\\b[^>]*>").find(tail)?.value
        val paper = setup?.let { X.attr(it, "paperSize") }?.toIntOrNull() ?: 9
        var (pw, ph) = when (paper) {
            1 -> 612f to 792f
            5 -> 612f to 1008f
            8 -> 842f to 1191f
            else -> 595f to 842f
        }
        if (setup?.let { X.attr(it, "orientation") } == "landscape") { val t = pw; pw = ph; ph = t }
        val mTag = Regex("<pageMargins\\b[^>]*>").find(tail)?.value
        fun mg(n: String, d: Float) = (mTag?.let { X.attr(it, n) }?.toFloatOrNull() ?: d) * 72f
        val ml = mg("left", 0.7f); val mr = mg("right", 0.7f); val mt = mg("top", 0.75f); val mb = mg("bottom", 0.75f)
        val printW = pw - ml - mr
        val printH = ph - mt - mb

        val maxR = sh.maxRow()
        val maxC = sh.maxCol()
        if (maxR < 0) return listOf(PreviewPage(pw, ph) { c ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 14f }
            c.drawText("Hoja vacía", ml, mt + 20f, p)
        })

        val fmt = Regex("<sheetFormatPr\\b[^>]*>").find(sh.head)?.value
        val defCharW = fmt?.let { X.attr(it, "defaultColWidth") }?.toFloatOrNull() ?: 8.43f
        val defRowH = fmt?.let { X.attr(it, "defaultRowHeight") }?.toFloatOrNull() ?: 15f
        fun colPt(c: Int): Float {
            val cd = sh.cols.firstOrNull { c in it.min..it.max }
            if (cd != null) {
                if (cd.attrs.contains("hidden=\"1\"") || cd.attrs.contains("hidden=\"true\"")) return 0f
                val wch = Regex("width=\"([\\d.]+)\"").find(cd.attrs)?.groupValues?.get(1)?.toFloatOrNull()
                if (wch != null) return (wch * 7f + 5f) * 0.75f
            }
            return (defCharW * 7f + 5f) * 0.75f
        }
        fun rowPt(r: Int): Float {
            val a = sh.rowAttrs[r] ?: return defRowH
            if (a.contains("hidden=\"1\"") || a.contains("hidden=\"true\"")) return 0f
            return Regex("ht=\"([\\d.]+)\"").find(a)?.groupValues?.get(1)?.toFloatOrNull() ?: defRowH
        }
        val colW = FloatArray(maxC + 1) { colPt(it) }
        val rowH = FloatArray(maxR + 1) { rowPt(it) }

        val fitPage = Regex("fitToPage=\"(1|true)\"").containsMatchIn(sh.head)
        var scale = (setup?.let { X.attr(it, "scale") }?.toFloatOrNull() ?: 100f) / 100f
        if (fitPage) {
            val fw = setup?.let { X.attr(it, "fitToWidth") }?.toIntOrNull() ?: 1
            val fh = setup?.let { X.attr(it, "fitToHeight") }?.toIntOrNull() ?: 1
            val sw = if (fw > 0) printW * fw / max(1f, colW.sum()) else Float.MAX_VALUE
            val shh = if (fh > 0) printH * fh / max(1f, rowH.sum()) else Float.MAX_VALUE
            scale = min(1f, min(sw, shh))
        }
        if (scale <= 0.05f) scale = 1f
        val availW = printW / scale
        val availH = printH / scale

        fun breaks(sizes: FloatArray, avail: Float): List<IntRange> {
            val out = ArrayList<IntRange>()
            var start = 0
            var acc = 0f
            for (i in sizes.indices) {
                if (acc + sizes[i] > avail && i > start) { out.add(start until i); start = i; acc = 0f }
                acc += sizes[i]
            }
            out.add(start until sizes.size)
            return out
        }
        val colBlocks = breaks(colW, availW)
        val rowBlocks = breaks(rowH, availH)

        // Textos ya evaluados (se dibuja sin volver a tocar el documento)
        val infos = HashMap<Long, Triple<String, CellStyle?, Boolean>>()
        for ((k, cell) in sh.cells) {
            val r = rowOf(k); val c = colOf(k)
            if (r > maxR || c > maxC) continue
            val st = doc.styles.style(cell.style)
            val text = doc.display(sh, r, c)
            if (text.isEmpty() && st.fill == null && !st.border) continue
            infos[k] = Triple(text, st, doc.value(sh, r, c) is Double)
        }

        val total = colBlocks.size * rowBlocks.size
        val pages = ArrayList<PreviewPage>()
        var n = 0
        for (cb in colBlocks) for (rb in rowBlocks) {
            n++
            val draws = ArrayList<CD>()
            var y = 0f
            for (r in rb) {
                var x = 0f
                for (c in cb) {
                    val inf = infos[(r.toLong() shl 20) or c.toLong()]
                    draws.add(CD(x, y, colW[c], rowH[r], r, c, inf?.first ?: "", inf?.second, inf?.third ?: false))
                    x += colW[c]
                }
                y += rowH[r]
            }
            val empty = HashSet<Long>()
            for (d in draws) if (d.text.isEmpty()) empty.add((d.r.toLong() shl 20) or d.c.toLong())
            val label = "${sh.name} · Página $n de $total"
            val pageNo = n
            pages.add(PreviewPage(pw, ph) { c -> drawPage(c, draws, empty, cb, ml, mt, pw, ph, printW, printH, scale, label, colW) })
        }
        return pages
    }

    private fun drawPage(
        c: Canvas, draws: List<CD>, empty: Set<Long>, cb: IntRange, ml: Float, mt: Float, pw: Float, ph: Float,
        printW: Float, printH: Float, scale: Float, label: String, colW: FloatArray
    ) {
        val fill = Paint()
        val grid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.4f; color = 0x33000000 }
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0xFF000000.toInt() }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.translate(ml, mt)
        c.clipRect(0f, 0f, printW, printH)
        c.scale(scale, scale)
        for (d in draws) {
            val st = d.style
            if (st?.fill != null) { fill.color = st.fill; fill.style = Paint.Style.FILL; c.drawRect(d.x, d.y, d.x + d.w, d.y + d.h, fill) }
            c.drawRect(d.x, d.y, d.x + d.w, d.y + d.h, grid)
            if (st?.border == true) c.drawRect(d.x, d.y, d.x + d.w, d.y + d.h, border)
        }
        for (d in draws) {
            if (d.text.isEmpty()) continue
            val st = d.style
            tp.textSize = (st?.size ?: 11f)
            tp.color = st?.color ?: Color.BLACK
            tp.typeface = Typeface.create(Typeface.SANS_SERIF, when {
                st?.bold == true && st.italic -> Typeface.BOLD_ITALIC
                st?.bold == true -> Typeface.BOLD
                st?.italic == true -> Typeface.ITALIC
                else -> Typeface.NORMAL
            })
            tp.isUnderlineText = st?.underline == true
            tp.isStrikeThruText = st?.strike == true
            val fm = tp.fontMetrics
            val by = d.y + (d.h - (fm.descent - fm.ascent)) / 2f - fm.ascent
            val align = st?.alignH ?: if (d.right) "right" else "left"
            // El texto largo se extiende sobre las celdas vacías contiguas, como en Excel
            var w = d.w
            if (align == "left") {
                var cc = d.c + 1
                while (cc in cb && (((d.r.toLong() shl 20) or cc.toLong()) in empty)) { w += colW[cc]; cc++ }
            }
            c.save()
            c.clipRect(d.x, d.y, d.x + w, d.y + d.h)
            val tw = tp.measureText(d.text)
            val tx = when (align) { "center" -> d.x + (d.w - tw) / 2f; "right" -> d.x + d.w - tw - 2f; else -> d.x + 2f }
            c.drawText(d.text, tx, by, tp)
            c.restore()
        }
        c.restore()
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 9f; textAlign = Paint.Align.CENTER }
        c.drawText(label, pw / 2f, ph - 14f, lp)
    }
}
