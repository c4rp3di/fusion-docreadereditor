package com.docreader.editor.ui.viewer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.docreader.editor.data.xlsx.CellStyle
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Lo que la cuadrícula necesita saber del documento y a quién avisar. Todas las medidas en «px de Excel». */
interface GridHost {
    fun rows(): Int
    fun cols(): Int
    fun colPx(c: Int): Float
    fun rowPx(r: Int): Float
    fun text(r: Int, c: Int): String
    fun style(r: Int, c: Int): CellStyle?
    fun isNumber(r: Int, c: Int): Boolean
    fun isEmpty(r: Int, c: Int): Boolean
    fun merges(): List<IntArray>
    fun freezeRows(): Int
    fun freezeCols(): Int
    fun selection(): IntArray          // r1, c1, r2, c2 normalizados
    fun active(): IntArray             // r, c
    fun onSelectCell(r: Int, c: Int)
    fun onSelectRange(anchorR: Int, anchorC: Int, r: Int, c: Int)
    fun onSelectCols(c1: Int, c2: Int)
    fun onSelectRows(r1: Int, r2: Int)
    fun onSelectAll()
    fun onEdit()
    fun onResizeCol(c: Int, px: Float)
    fun onResizeRow(r: Int, px: Float)
    fun onAutoFitCol(c: Int)
    fun onNeedMore(rowsNear: Boolean, colsNear: Boolean)
    fun onHeaderLongPress(isCol: Boolean, index: Int, anchor: View)
}

/**
 * Hoja de cálculo dibujada a mano (como Google Sheets): cabeceras A, B, C… y 1, 2, 3… siempre visibles,
 * paneles inmovilizados, anchos y alturas variables (se redimensionan arrastrando la cabecera), selección con
 * borde azul, rango arrastrando el asa o con pulsación larga, celdas combinadas, texto que desborda y zoom por pellizco.
 */
class SheetGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var host: GridHost? = null
        set(v) { field = v; relayout() }
    var zoom = 1f
        private set

    private enum class Mode { NONE, RESIZE_COL, RESIZE_ROW, HANDLE, SELECT }

    private val d = resources.displayMetrics.density
    private val unit = 1.4f * d
    private val hdrW = 44f * d
    private val hdrH = 26f * d
    private var offX = 0f
    private var offY = 0f
    private var colX = FloatArray(2)
    private var rowY = FloatArray(2)
    private var nCols = 0
    private var nRows = 0
    private var fc = 0
    private var fr = 0
    private var frozenW = 0f
    private var frozenH = 0f
    private var mode = Mode.NONE
    private var anchorR = 0
    private var anchorC = 0
    private var resizeIdx = -1
    private var resizeStartPx = 0f
    private var downX = 0f
    private var downY = 0f
    private var liveCol = -1
    private var liveColPx = 0f
    private var liveRow = -1
    private var liveRowPx = 0f
    private var growing = false
    private val mergeOrigin = HashMap<Long, IntArray>()
    private val mergeInner = HashSet<Long>()

    // región que se está dibujando
    private var regFC = false
    private var regFR = false
    private var regCB = 0

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cBg = if (dark) 0xFF1E1E1E.toInt() else Color.WHITE
    private val cGrid = if (dark) 0xFF3C3F43.toInt() else 0xFFE1E3E6.toInt()
    private val cText = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    private val cHdrBg = if (dark) 0xFF2A2B2E.toInt() else 0xFFF8F9FA.toInt()
    private val cHdrText = if (dark) 0xFFBDC1C6.toInt() else 0xFF5F6368.toInt()
    private val cHdrLine = if (dark) 0xFF55585C.toInt() else 0xFFC4C7C5.toInt()
    private val cHdrSel = if (dark) 0xFF2F4468.toInt() else 0xFFD3E3FD.toInt()
    private val cHdrSelText = if (dark) 0xFFA8C7FA.toInt() else 0xFF0B57D0.toInt()
    private val cSel = 0xFF1A73E8.toInt()
    private val cSelFill = 0x261A73E8

    private val fillP = Paint()
    private val lineP = Paint().apply { style = Paint.Style.STROKE }
    private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(context)

    private val scaleDet = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { mode = Mode.NONE; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoomAt((zoom * detector.scaleFactor).coerceIn(0.5f, 3f), detector.focusX, detector.focusY)
            return true
        }
    })

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); startTouch(e); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { doubleTap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) { longPress(e.x, e.y) }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode == Mode.NONE && !scaleDet.isInProgress) { panBy(dx, dy); return true }
            return false
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (mode == Mode.NONE && !scaleDet.isInProgress) { fling(-vx, -vy); return true }
            return false
        }
    })

    // ------------------------------------------------------------ geometría
    private fun liveColU(c: Int, px: Float) = if (c == liveCol) liveColPx else px
    private fun liveRowU(r: Int, px: Float) = if (r == liveRow) liveRowPx else px

    /** Recalcula posiciones acumuladas (tras cambios de tamaño, inmovilizado, nº de filas/columnas...). */
    fun relayout() {
        val h = host ?: return
        nCols = h.cols()
        nRows = h.rows()
        if (colX.size != nCols + 1) colX = FloatArray(nCols + 1)
        if (rowY.size != nRows + 1) rowY = FloatArray(nRows + 1)
        var x = 0f
        for (c in 0 until nCols) { colX[c] = x; x += liveColU(c, h.colPx(c)) * unit }
        colX[nCols] = x
        var y = 0f
        for (r in 0 until nRows) { rowY[r] = y; y += liveRowU(r, h.rowPx(r)) * unit }
        rowY[nRows] = y
        fc = h.freezeCols().coerceIn(0, nCols)
        fr = h.freezeRows().coerceIn(0, nRows)
        frozenW = colX[fc]
        frozenH = rowY[fr]
        // si lo inmovilizado ocupa casi toda la pantalla, se ignora para no dejar la hoja inservible
        if (width > 0 && frozenW * zoom > (width - hdrW) * 0.7f) { fc = 0; frozenW = 0f }
        if (height > 0 && frozenH * zoom > (height - hdrH) * 0.7f) { fr = 0; frozenH = 0f }
        mergeOrigin.clear(); mergeInner.clear()
        for (m in h.merges()) {
            mergeOrigin[key(m[0], m[1])] = m
            if ((m[2] - m[0] + 1).toLong() * (m[3] - m[1] + 1) <= 20000) {
                for (r in m[0]..m[2]) for (c in m[1]..m[3]) if (r != m[0] || c != m[1]) mergeInner.add(key(r, c))
            }
        }
        clampScroll()
        invalidate()
    }

    private fun key(r: Int, c: Int) = (r.toLong() shl 20) or c.toLong()

    private fun maxScrollX() = max(0f, (colX[nCols] - frozenW) * zoom - (width - hdrW - frozenW * zoom) + 80f * d)
    private fun maxScrollY() = max(0f, (rowY[nRows] - frozenH) * zoom - (height - hdrH - frozenH * zoom) + 80f * d)

    private fun clampScroll() {
        offX = offX.coerceIn(0f, maxScrollX())
        offY = offY.coerceIn(0f, maxScrollY())
    }

    private fun xFrozen(c: Int) = hdrW + colX[c] * zoom
    private fun xScroll(c: Int) = hdrW + frozenW * zoom + (colX[c] - frozenW) * zoom - offX
    private fun yFrozen(r: Int) = hdrH + rowY[r] * zoom
    private fun yScroll(r: Int) = hdrH + frozenH * zoom + (rowY[r] - frozenH) * zoom - offY
    private fun xs(c: Int) = if (c < fc) xFrozen(c) else xScroll(c)
    private fun ys(r: Int) = if (r < fr) yFrozen(r) else yScroll(r)
    private fun X(c: Int) = if (regFC) xFrozen(c) else xScroll(c)
    private fun Y(r: Int) = if (regFR) yFrozen(r) else yScroll(r)

    private fun idxAt(a: FloatArray, v: Float): Int {
        var lo = 0
        var hi = a.size - 2
        if (hi < 0) return 0
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (a[mid] <= v) lo = mid else hi = mid - 1 }
        return lo
    }

    private fun colAtX(x: Float): Int {
        val fW = frozenW * zoom
        val u = if (x < hdrW + fW) (x - hdrW) / zoom else frozenW + (x - hdrW - fW + offX) / zoom
        return idxAt(colX, max(0f, u))
    }

    private fun rowAtY(y: Float): Int {
        val fH = frozenH * zoom
        val u = if (y < hdrH + fH) (y - hdrH) / zoom else frozenH + (y - hdrH - fH + offY) / zoom
        return idxAt(rowY, max(0f, u))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); relayout() }

    // ------------------------------------------------------------ dibujo
    override fun onDraw(canvas: Canvas) {
        val h = host ?: return
        canvas.drawColor(cBg)
        if (nCols == 0 || nRows == 0) return
        val w = width.toFloat()
        val hh = height.toFloat()
        val fW = frozenW * zoom
        val fH = frozenH * zoom
        val c0 = max(fc, idxAt(colX, frozenW + offX / zoom))
        val c1 = idxAt(colX, frozenW + (offX + w - hdrW - fW) / zoom)
        val r0 = max(fr, idxAt(rowY, frozenH + offY / zoom))
        val r1 = idxAt(rowY, frozenH + (offY + hh - hdrH - fH) / zoom)
        val sel = h.selection()
        val act = h.active()
        // cuatro zonas: esquina inmovilizada, filas fijas, columnas fijas y zona desplazable
        region(canvas, h, 0, fr - 1, 0, fc - 1, RectF(hdrW, hdrH, hdrW + fW, hdrH + fH), true, true, sel, act)
        region(canvas, h, 0, fr - 1, c0, c1, RectF(hdrW + fW, hdrH, w, hdrH + fH), false, true, sel, act)
        region(canvas, h, r0, r1, 0, fc - 1, RectF(hdrW, hdrH + fH, hdrW + fW, hh), true, false, sel, act)
        region(canvas, h, r0, r1, c0, c1, RectF(hdrW + fW, hdrH + fH, w, hh), false, false, sel, act)
        drawHeaders(canvas, c0, c1, r0, r1, sel)
        if (fc > 0) { lineP.color = 0xFF9AA0A6.toInt(); lineP.strokeWidth = 2f * d; canvas.drawLine(hdrW + fW, 0f, hdrW + fW, hh, lineP) }
        if (fr > 0) { lineP.color = 0xFF9AA0A6.toInt(); lineP.strokeWidth = 2f * d; canvas.drawLine(0f, hdrH + fH, w, hdrH + fH, lineP) }
    }

    private fun region(
        canvas: Canvas, h: GridHost, rA: Int, rB: Int, cA: Int, cB: Int, clip: RectF,
        frozenCols: Boolean, frozenRows: Boolean, sel: IntArray, act: IntArray
    ) {
        if (rA > rB || cA > cB || clip.width() <= 0f || clip.height() <= 0f) return
        regFC = frozenCols; regFR = frozenRows; regCB = cB
        canvas.save()
        canvas.clipRect(clip)
        // 1) fondos de celda
        for (r in rA..rB) {
            val y = Y(r); val y2 = Y(r + 1)
            if (y2 - y < 0.5f) continue
            for (c in cA..cB) {
                if (mergeInner.contains(key(r, c)) || mergeOrigin.containsKey(key(r, c))) continue
                val x = X(c); val x2 = X(c + 1)
                if (x2 - x < 0.5f) continue
                val f = h.style(r, c)?.fill ?: continue
                fillP.color = f
                canvas.drawRect(x, y, x2, y2, fillP)
            }
        }
        // 2) líneas de cuadrícula
        lineP.color = cGrid
        lineP.strokeWidth = 1f
        for (r in rA..rB + 1) { val y = Y(r); canvas.drawLine(X(cA), y, X(cB + 1), y, lineP) }
        for (c in cA..cB + 1) { val x = X(c); canvas.drawLine(x, Y(rA), x, Y(rB + 1), lineP) }
        // 3) combinadas y 4) contenido
        for (m in mergeOrigin.values) {
            if (m[2] < rA || m[0] > rB || m[3] < cA || m[1] > cB) continue
            val x = X(m[1]); val y = Y(m[0]); val x2 = X(m[3] + 1); val y2 = Y(m[2] + 1)
            val st = h.style(m[0], m[1])
            fillP.color = st?.fill ?: cBg
            canvas.drawRect(x, y, x2, y2, fillP)
            lineP.color = cGrid; lineP.strokeWidth = 1f
            canvas.drawRect(x, y, x2, y2, lineP)
            content(canvas, h, m[0], m[1], x, y, x2, y2, false)
        }
        for (r in rA..rB) {
            val y = Y(r); val y2 = Y(r + 1)
            if (y2 - y < 0.5f) continue
            for (c in cA..cB) {
                val k = key(r, c)
                if (mergeInner.contains(k) || mergeOrigin.containsKey(k)) continue
                val x = X(c); val x2 = X(c + 1)
                if (x2 - x < 0.5f) continue
                content(canvas, h, r, c, x, y, x2, y2, true)
            }
        }
        // 5) selección
        drawSelection(canvas, h, sel, act, clip)
        canvas.restore()
    }

    private fun content(canvas: Canvas, h: GridHost, r: Int, c: Int, x: Float, y: Float, x2: Float, y2: Float, canOverflow: Boolean) {
        val st = h.style(r, c)
        if (st?.border == true) {
            lineP.color = if (dark) 0xFFCCCCCC.toInt() else Color.BLACK
            lineP.strokeWidth = 1.3f * d
            canvas.drawRect(x, y, x2, y2, lineP)
        }
        val text = h.text(r, c)
        if (text.isEmpty()) return
        textP.textSize = (st?.size ?: 11f) * 1.45f * d * zoom
        textP.color = st?.color ?: cText
        textP.typeface = Typeface.create(Typeface.SANS_SERIF, when {
            st?.bold == true && st.italic -> Typeface.BOLD_ITALIC
            st?.bold == true -> Typeface.BOLD
            st?.italic == true -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })
        textP.isUnderlineText = st?.underline == true
        textP.isStrikeThruText = st?.strike == true
        val num = h.isNumber(r, c)
        val align = st?.alignH ?: if (num) "right" else "left"
        val pad = 4f * d * zoom
        val fm = textP.fontMetrics
        val baseline = (y + y2) / 2f - (fm.ascent + fm.descent) / 2f
        val tw = textP.measureText(text)
        var clipR = x2
        if (canOverflow && align == "left" && !num && tw > (x2 - x - 2 * pad)) {
            var cc = c + 1
            while (cc <= regCB && h.isEmpty(r, cc) && clipR < x + tw + 2 * pad) { clipR = X(cc + 1); cc++ }
        }
        canvas.save()
        canvas.clipRect(x + 1f, y + 1f, clipR - 1f, y2 - 1f)
        val tx = when (align) {
            "center" -> x + (x2 - x - tw) / 2f
            "right" -> x2 - pad - tw
            else -> x + pad
        }
        canvas.drawText(text, tx, baseline, textP)
        canvas.restore()
    }

    private fun drawSelection(canvas: Canvas, h: GridHost, sel: IntArray, act: IntArray, clip: RectF) {
        val sx1 = X(sel[1]); val sx2 = X(sel[3] + 1); val sy1 = Y(sel[0]); val sy2 = Y(sel[2] + 1)
        val am = mergeOrigin[key(act[0], act[1])]
        val ax1 = X(act[1]); val ay1 = Y(act[0])
        val ax2 = if (am != null) X(am[3] + 1) else X(act[1] + 1)
        val ay2 = if (am != null) Y(am[2] + 1) else Y(act[0] + 1)
        val multi = sel[0] != sel[2] || sel[1] != sel[3]
        if (multi) {
            fillP.color = cSelFill
            canvas.drawRect(sx1, sy1, sx2, ay1, fillP)
            canvas.drawRect(sx1, ay2, sx2, sy2, fillP)
            canvas.drawRect(sx1, ay1, ax1, ay2, fillP)
            canvas.drawRect(ax2, ay1, sx2, ay2, fillP)
        }
        lineP.color = cSel
        lineP.strokeWidth = 2f * d
        canvas.drawRect(sx1, sy1, sx2, sy2, lineP)
        if (multi) { lineP.strokeWidth = 1.5f * d; canvas.drawRect(ax1, ay1, ax2, ay2, lineP) }
        // asa para ampliar el rango
        if (sx2 >= clip.left && sx2 <= clip.right && sy2 >= clip.top && sy2 <= clip.bottom) {
            fillP.color = cSel
            canvas.drawCircle(sx2, sy2, 7f * d, fillP)
            fillP.color = cBg
            canvas.drawCircle(sx2, sy2, 3f * d, fillP)
        }
    }

    private fun letters(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { n--; sb.append('A' + n % 26); n /= 26 }
        return sb.reverse().toString()
    }

    private fun drawHeaders(canvas: Canvas, c0: Int, c1: Int, r0: Int, r1: Int, sel: IntArray) {
        val w = width.toFloat()
        val hh = height.toFloat()
        val fW = frozenW * zoom
        val fH = frozenH * zoom
        textP.typeface = Typeface.DEFAULT
        textP.isUnderlineText = false
        textP.isStrikeThruText = false
        textP.textSize = 12f * d
        fun colCell(c: Int, x: Float, x2: Float) {
            if (x2 - x < 0.5f) return
            val on = c in sel[1]..sel[3]
            fillP.color = if (on) cHdrSel else cHdrBg
            canvas.drawRect(x, 0f, x2, hdrH, fillP)
            lineP.color = cHdrLine; lineP.strokeWidth = 1f
            canvas.drawLine(x2, 0f, x2, hdrH, lineP)
            textP.color = if (on) cHdrSelText else cHdrText
            val t = letters(c)
            val fm = textP.fontMetrics
            canvas.drawText(t, (x + x2 - textP.measureText(t)) / 2f, hdrH / 2f - (fm.ascent + fm.descent) / 2f, textP)
        }
        fun rowCell(r: Int, y: Float, y2: Float) {
            if (y2 - y < 0.5f) return
            val on = r in sel[0]..sel[2]
            fillP.color = if (on) cHdrSel else cHdrBg
            canvas.drawRect(0f, y, hdrW, y2, fillP)
            lineP.color = cHdrLine; lineP.strokeWidth = 1f
            canvas.drawLine(0f, y2, hdrW, y2, lineP)
            textP.color = if (on) cHdrSelText else cHdrText
            val t = "${r + 1}"
            val fm = textP.fontMetrics
            canvas.drawText(t, (hdrW - textP.measureText(t)) / 2f, (y + y2) / 2f - (fm.ascent + fm.descent) / 2f, textP)
        }
        canvas.save(); canvas.clipRect(hdrW + fW, 0f, w, hdrH)
        for (c in c0..c1) colCell(c, xScroll(c), xScroll(c + 1))
        canvas.restore()
        canvas.save(); canvas.clipRect(hdrW, 0f, hdrW + fW, hdrH)
        for (c in 0 until fc) colCell(c, xFrozen(c), xFrozen(c + 1))
        canvas.restore()
        canvas.save(); canvas.clipRect(0f, hdrH + fH, hdrW, hh)
        for (r in r0..r1) rowCell(r, yScroll(r), yScroll(r + 1))
        canvas.restore()
        canvas.save(); canvas.clipRect(0f, hdrH, hdrW, hdrH + fH)
        for (r in 0 until fr) rowCell(r, yFrozen(r), yFrozen(r + 1))
        canvas.restore()
        fillP.color = cHdrBg
        canvas.drawRect(0f, 0f, hdrW, hdrH, fillP)
        lineP.color = cHdrLine; lineP.strokeWidth = 1f
        canvas.drawLine(0f, hdrH, hdrW, hdrH, lineP)
        canvas.drawLine(hdrW, 0f, hdrW, hdrH, lineP)
        lineP.color = cHdrLine
        canvas.drawLine(hdrW, 0f, hdrW, hh, lineP)
        canvas.drawLine(0f, hdrH, w, hdrH, lineP)
    }

    // ------------------------------------------------------------ táctil
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        if (scaleDet.isInProgress) return true
        gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1) move(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishTouch()
        }
        return true
    }

    private fun startTouch(e: MotionEvent) {
        val h = host ?: return
        mode = Mode.NONE
        downX = e.x; downY = e.y
        val near = 14f * d
        if (e.y < hdrH && e.x > hdrW) {
            val cols = (0 until fc).toList() + (max(fc, idxAt(colX, frozenW + offX / zoom))..idxAt(colX, frozenW + (offX + width) / zoom))
            for (c in cols) {
                val right = xs(c + 1)
                if (right > hdrW && abs(e.x - right) < near && colX[c + 1] > colX[c]) {
                    mode = Mode.RESIZE_COL; resizeIdx = c; resizeStartPx = h.colPx(c); return
                }
            }
        }
        if (e.x < hdrW && e.y > hdrH) {
            val rows = (0 until fr).toList() + (max(fr, idxAt(rowY, frozenH + offY / zoom))..idxAt(rowY, frozenH + (offY + height) / zoom))
            for (r in rows) {
                val bottom = ys(r + 1)
                if (bottom > hdrH && abs(e.y - bottom) < near && rowY[r + 1] > rowY[r]) {
                    mode = Mode.RESIZE_ROW; resizeIdx = r; resizeStartPx = h.rowPx(r); return
                }
            }
        }
        val sel = h.selection()
        val hx = xs(sel[3] + 1)
        val hy = ys(sel[2] + 1)
        if (hypot(e.x - hx, e.y - hy) < 26f * d && hx > hdrW && hy > hdrH) {
            mode = Mode.HANDLE; anchorR = sel[0]; anchorC = sel[1]
        }
    }

    private fun move(e: MotionEvent) {
        val h = host ?: return
        when (mode) {
            Mode.RESIZE_COL -> { liveCol = resizeIdx; liveColPx = max(14f, resizeStartPx + (e.x - downX) / (zoom * unit)); relayout() }
            Mode.RESIZE_ROW -> { liveRow = resizeIdx; liveRowPx = max(8f, resizeStartPx + (e.y - downY) / (zoom * unit)); relayout() }
            Mode.HANDLE, Mode.SELECT -> {
                val x = e.x.coerceIn(hdrW + 1f, width - 1f)
                val y = e.y.coerceIn(hdrH + 1f, height - 1f)
                val edge = 36f * d
                var dx = 0f
                var dy = 0f
                if (e.x > width - edge) dx = 16f * d else if (e.x < hdrW + frozenW * zoom + edge) dx = -16f * d
                if (e.y > height - edge) dy = 16f * d else if (e.y < hdrH + frozenH * zoom + edge) dy = -16f * d
                if (dx != 0f || dy != 0f) panBy(dx, dy)
                h.onSelectRange(anchorR, anchorC, rowAtY(y), colAtX(x))
            }
            Mode.NONE -> Unit
        }
    }

    private fun finishTouch() {
        val h = host
        when (mode) {
            Mode.RESIZE_COL -> { val c = resizeIdx; val px = liveColPx; liveCol = -1; h?.onResizeCol(c, px); relayout() }
            Mode.RESIZE_ROW -> { val r = resizeIdx; val px = liveRowPx; liveRow = -1; h?.onResizeRow(r, px); relayout() }
            else -> Unit
        }
        mode = Mode.NONE
    }

    private fun tap(x: Float, y: Float) {
        val h = host ?: return
        if (mode != Mode.NONE) return
        when {
            x < hdrW && y < hdrH -> h.onSelectAll()
            y < hdrH -> { val c = colAtX(x); h.onSelectCols(c, c) }
            x < hdrW -> { val r = rowAtY(y); h.onSelectRows(r, r) }
            else -> h.onSelectCell(rowAtY(y), colAtX(x))
        }
    }

    private fun doubleTap(x: Float, y: Float) {
        val h = host ?: return
        if (mode != Mode.NONE) { return }
        if (y < hdrH && x > hdrW) {
            val c = colAtX(x)
            val near = 14f * d
            if (abs(x - xs(c + 1)) < near) { h.onAutoFitCol(c); return }
            if (c > 0 && abs(x - xs(c)) < near) { h.onAutoFitCol(c - 1); return }
            return
        }
        if (x > hdrW && y > hdrH) { h.onSelectCell(rowAtY(y), colAtX(x)); h.onEdit() }
    }

    private fun longPress(x: Float, y: Float) {
        val h = host ?: return
        if (mode != Mode.NONE) return
        when {
            x < hdrW && y < hdrH -> Unit
            y < hdrH -> { val c = colAtX(x); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); h.onHeaderLongPress(true, c, this) }
            x < hdrW -> { val r = rowAtY(y); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); h.onHeaderLongPress(false, r, this) }
            else -> {
                val r = rowAtY(y); val c = colAtX(x)
                mode = Mode.SELECT; anchorR = r; anchorC = c
                h.onSelectCell(r, c)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    // ------------------------------------------------------------ desplazamiento y zoom
    private fun panBy(dx: Float, dy: Float) {
        offX = (offX + dx).coerceIn(0f, maxScrollX())
        offY = (offY + dy).coerceIn(0f, maxScrollY())
        invalidate()
        checkMore()
    }

    private fun fling(vx: Float, vy: Float) {
        scroller.fling(offX.toInt(), offY.toInt(), vx.toInt(), vy.toInt(), 0, maxScrollX().toInt(), 0, maxScrollY().toInt())
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
            checkMore()
        }
    }

    private fun checkMore() {
        if (growing) return
        val rowsNear = offY > maxScrollY() - 160f * d
        val colsNear = offX > maxScrollX() - 160f * d
        if (rowsNear || colsNear) {
            growing = true
            host?.onNeedMore(rowsNear, colsNear)
            growing = false
        }
    }

    private fun setZoomAt(nz: Float, fx: Float, fy: Float) {
        val old = zoom
        if (abs(nz - old) < 0.001f) return
        val fW = frozenW * old
        val fH = frozenH * old
        val ux = if (fx > hdrW + fW) (offX + fx - hdrW - fW) / old else null
        val uy = if (fy > hdrH + fH) (offY + fy - hdrH - fH) / old else null
        zoom = nz
        if (ux != null) offX = ux * nz - (fx - hdrW - frozenW * nz)
        if (uy != null) offY = uy * nz - (fy - hdrH - frozenH * nz)
        relayout()
    }

    fun zoomBy(f: Float) = setZoomAt((zoom * f).coerceIn(0.5f, 3f), width / 2f, height / 2f)

    /** Desplaza lo necesario para que la celda quede visible. */
    fun scrollToCell(r: Int, c: Int) {
        if (nCols == 0 || nRows == 0) return
        val cc = c.coerceIn(0, nCols - 1)
        val rr = r.coerceIn(0, nRows - 1)
        if (cc >= fc) {
            val left = (colX[cc] - frozenW) * zoom
            val right = (colX[cc + 1] - frozenW) * zoom
            val viewW = width - hdrW - frozenW * zoom
            if (left < offX) offX = left else if (right > offX + viewW) offX = right - viewW
        }
        if (rr >= fr) {
            val top = (rowY[rr] - frozenH) * zoom
            val bottom = (rowY[rr + 1] - frozenH) * zoom
            val viewH = height - hdrH - frozenH * zoom
            if (top < offY) offY = top else if (bottom > offY + viewH) offY = bottom - viewH
        }
        clampScroll()
        invalidate()
    }

    fun resetScroll() { offX = 0f; offY = 0f; invalidate() }
}
