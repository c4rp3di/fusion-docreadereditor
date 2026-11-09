package com.docreader.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.data.pdf.DocxBuilder
import com.docreader.editor.data.pdf.FormField
import com.docreader.editor.data.pdf.CoverText
import com.docreader.editor.data.pdf.ImageStamp
import com.docreader.editor.data.pdf.Ink
import com.docreader.editor.data.pdf.Markup
import com.docreader.editor.data.pdf.PWord
import com.docreader.editor.data.pdf.PdfAnnot
import com.docreader.editor.data.pdf.PdfSession
import com.docreader.editor.data.pdf.PdfTextExtractor
import com.docreader.editor.data.pdf.TextBox
import com.docreader.editor.databinding.FragmentViewerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Visor/editor PDF: texto seleccionable y copiable, anotaciones, firma, sellos, formularios y páginas. */
class PdfEditorController(
    private val ctx: Context,
    private val b: FragmentViewerBinding,
    val session: PdfSession,
    private val scope: CoroutineScope,
    private val pickPdf: () -> Unit,
    private val requestTarget: (kind: String, mime: String, name: String) -> Unit
) {
    private enum class Mode { SELECT, DRAW, TEXT, SIGN, STAMP, ERASE }

    private val tb get() = b.pdfToolbar
    private val sb get() = b.pdfSelBar
    private var mode = Mode.SELECT
    private var penColor = 0xFFE53935.toInt()
    private var penWidth = 2.5f
    private var zoom = 1f

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var sizes: List<Pair<Float, Float>> = emptyList()
    private val renderMutex = Mutex()
    private val rendering = HashSet<Int>()
    private val cache = object : LruCache<Int, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private var extractor = PdfTextExtractor(session.work)
    private val wordCache = HashMap<Int, List<PWord>>()
    private val loading = HashSet<Int>()
    private var hinted = false
    private var night = false
    private var pinch: PinchZoom? = null
    private var detailJob: Job? = null
    private val adapter = PdfPagesAdapter(this)

    // selección
    private var selPage = -1
    private var selLo = -1
    private var selHi = -1
    private var dragging = 0
    private val handler = Handler(Looper.getMainLooper())
    private var longRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var strokePts: ArrayList<PointF>? = null
    private var strokePage = -1

    // búsqueda
    private val hits = HashMap<Int, List<RectF>>()
    private var findPage = 0
    private var findFrom = 0

    private var pendingPages: List<Int> = emptyList()
    private var pendingDocx: ByteArray = ByteArray(0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        b.pages.layoutManager = LinearLayoutManager(ctx)
        b.pages.itemAnimator = null
        b.pages.adapter = adapter
        bindToolbar()
        bindSelectionBar()
        bindPinch()
        applyZoom()
        setMode(Mode.SELECT)
        scope.launch { openRenderer(); adapter.notifyDataSetChanged() }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    private fun widthPx() = (ctx.resources.displayMetrics.widthPixels * zoom).toInt()

    // ------------------------------------------------------------ render
    fun pageCount() = sizes.size
    fun pageSize(i: Int): Pair<Float, Float> = sizes.getOrElse(i) { 595f to 842f }
    fun cachedBitmap(i: Int): Bitmap? = cache.get(i)

    private suspend fun openRenderer() = withContext(Dispatchers.IO) {
        closeRenderer()
        val p = ParcelFileDescriptor.open(session.work, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = p
        val r = PdfRenderer(p)
        renderer = r
        sizes = (0 until r.pageCount).map { i -> r.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
    }

    private fun closeRenderer() {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null
        pfd = null
    }

    fun onPageBound(v: PdfPageView) {
        val p = v.pageIndex
        if (v.night != night) v.night = night
        if (cache.get(p) == null && rendering.add(p)) {
            scope.launch {
                val bmp = renderPage(p)
                rendering.remove(p)
                if (bmp != null) {
                    cache.put(p, bmp)
                    if (v.pageIndex == p) { v.bitmap = bmp; v.invalidate() }
                }
            }
        }
        loadWords(p)
        if (zoom > 1.15f) scheduleDetail()
    }

    fun currentZoom() = zoom

    private suspend fun renderPage(i: Int): Bitmap? = renderMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val r = renderer ?: return@withContext null
                r.openPage(i).use { pg ->
                    val w = min(max(widthPx(), ctx.resources.displayMetrics.widthPixels), 1600)
                    val h = (w.toFloat() / pg.width * pg.height).toInt()
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun loadWords(p: Int) {
        if (wordCache.containsKey(p) || !loading.add(p)) return
        scope.launch {
            val w = extractor.allWords(p)
            wordCache[p] = w
            loading.remove(p)
            invalidateAll()
            if (w.isEmpty() && !hinted) {
                hinted = true
                toast("Hay páginas sin texto seleccionable (escaneadas). Usa «Texto ▾ → OCR».")
            }
        }
    }

    private fun invalidateAll() {
        for (i in 0 until b.pages.childCount) b.pages.getChildAt(i).invalidate()
    }

    private fun currentPage(): Int {
        val lm = b.pages.layoutManager as LinearLayoutManager
        var p = lm.findFirstCompletelyVisibleItemPosition()
        if (p < 0) p = lm.findFirstVisibleItemPosition()
        return max(0, p)
    }

    // ------------------------------------------------------------ zoom
    private fun bindPinch() {
        pinch = PinchZoom(
            b.pages, 1f, 4f, { zoom },
            { if (mode == Mode.SELECT && selLo < 0) (if (zoom < 1.5f) 2.5f else 1f) else null },
            ::commitZoom
        )
        b.pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleDetail()
            }
        })
        b.pdfScroll.setOnScrollChangeListener { _, _, _, _, _ -> scheduleDetail() }
    }

    /**
     * Zoom sin parpadeos: las páginas conservan la imagen que ya tenían (ampliada) y la cambian por la nítida
     * cuando termina de dibujarse; el punto bajo los dedos se mantiene fijo.
     */
    private fun commitZoom(nz: Float, r: Float, ax: Float, ay: Float, fx: Float, fy: Float) {
        val sx = b.pdfScroll.scrollX
        val child = b.pages.findChildViewUnder(ax, ay)
        val pos = if (child != null) b.pages.getChildAdapterPosition(child) else -1
        val h0 = child?.height ?: 0
        val frac = if (child != null && h0 > 0) (ay - child.top) / h0 else 0f
        zoom = nz
        applyZoom()
        b.pdfScroll.doOnNextLayout { b.pdfScroll.scrollTo(max(0, (ax * r - (fx - sx)).toInt()), 0) }
        if (pos >= 0) {
            (b.pages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, (fy - frac * h0 * r).toInt())
        }
    }

    private fun zoomButton(f: Float) {
        val nz = (zoom * f).coerceIn(1f, 4f)
        if (abs(nz - zoom) < 0.01f) return
        val cx = b.pdfScroll.scrollX + b.pdfScroll.width / 2f
        val cy = b.pages.height / 2f
        pinch?.animateTo(nz, cx, cy)
    }

    private fun applyZoom() {
        b.pages.layoutParams = b.pages.layoutParams.apply { width = widthPx() }
        clearDetails()
        scheduleDetail()
    }

    // ------------------------------------------------------------ detalle de alta resolución (teselas)
    private fun clearDetails() {
        for (i in 0 until b.pages.childCount) (b.pages.getChildAt(i) as? PdfPageView)?.detail = null
    }

    private fun visibleRect(v: PdfPageView): RectF? {
        val vw = v.width.toFloat()
        val vh = v.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null
        val top = max(0f, -v.top.toFloat())
        val bottom = min(vh, b.pages.height - v.top.toFloat())
        val left = b.pdfScroll.scrollX.toFloat()
        val right = min(vw, left + b.pdfScroll.width)
        if (bottom <= top || right <= left) return null
        val mx = (right - left) * 0.35f
        val my = (bottom - top) * 0.35f
        return RectF(max(0f, left - mx), max(0f, top - my), min(vw, right + mx), min(vh, bottom + my))
    }

    private fun scheduleDetail() {
        detailJob?.cancel()
        detailJob = scope.launch {
            delay(110)
            if (zoom < 1.15f) { clearDetails(); return@launch }
            val views = (0 until b.pages.childCount).mapNotNull { b.pages.getChildAt(it) as? PdfPageView }
            for (v in views) {
                val rect = visibleRect(v) ?: continue
                if (v.detail != null && v.detailZoom == zoom && v.detailRect.contains(rect)) continue
                val page = v.pageIndex
                val bmp = renderDetail(page, v.width.toFloat(), rect) ?: continue
                if (v.pageIndex != page || !v.isAttachedToWindow) continue
                v.detail = bmp
                v.detailRect.set(rect)
                v.detailZoom = zoom
                v.invalidate()
            }
        }
    }

    private suspend fun renderDetail(i: Int, viewW: Float, rect: RectF): Bitmap? = renderMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val r = renderer ?: return@withContext null
                r.openPage(i).use { pg ->
                    val sc = viewW / pg.width
                    val w = rect.width()
                    val h = rect.height()
                    val k = if (w * h > 4_500_000f) kotlin.math.sqrt(4_500_000f / (w * h)) else 1f
                    val bmp = Bitmap.createBitmap(max(1, (w * k).toInt()), max(1, (h * k).toInt()), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix()
                    m.setScale(sc * k, sc * k)
                    m.postTranslate(-rect.left * k, -rect.top * k)
                    pg.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ------------------------------------------------------------ dibujo de la capa superior
    fun drawOverlay(canvas: Canvas, v: PdfPageView) {
        val s = v.scale
        val p = v.pageIndex
        for (a in session.annots) if (a.page == p) drawAnnot(canvas, a, s)
        hits[p]?.let { list ->
            paint.style = Paint.Style.FILL
            paint.color = 0x66FF9800
            for (r in list) canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, paint)
        }
        if (selPage == p && selLo >= 0) {
            val ws = wordCache[p]
            if (ws != null) {
                paint.style = Paint.Style.FILL
                paint.color = 0x553B82F6
                for (i in selLo..min(selHi, ws.size - 1)) {
                    val w = ws[i]
                    canvas.drawRect(w.l * s, w.t * s, w.r * s, w.b * s, paint)
                }
                paint.color = 0xFF3B82F6.toInt()
                val a = ws.getOrNull(selLo)
                val z = ws.getOrNull(min(selHi, ws.size - 1))
                if (a != null) canvas.drawCircle(a.l * s, a.b * s + dp(6), dp(8).toFloat(), paint)
                if (z != null) canvas.drawCircle(z.r * s, z.b * s + dp(6), dp(8).toFloat(), paint)
            }
        }
        val pts = strokePts
        if (pts != null && strokePage == p && pts.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.color = penColor
            paint.strokeWidth = penWidth * s
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val path = Path()
            path.moveTo(pts[0].x * s, pts[0].y * s)
            for (i in 1 until pts.size) path.lineTo(pts[i].x * s, pts[i].y * s)
            canvas.drawPath(path, paint)
        }
    }

    private fun drawAnnot(canvas: Canvas, a: PdfAnnot, s: Float) {
        paint.reset(); paint.isAntiAlias = true
        when (a) {
            is Markup -> for (r in a.rects) {
                when (a.kind) {
                    Markup.HIGHLIGHT -> { paint.style = Paint.Style.FILL; paint.color = (a.color and 0x00FFFFFF) or 0x66000000; canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, paint) }
                    else -> {
                        paint.style = Paint.Style.STROKE; paint.color = a.color; paint.strokeWidth = max(1.5f, s)
                        val y = (if (a.kind == Markup.UNDERLINE) r.bottom else (r.top + r.bottom) / 2) * s
                        canvas.drawLine(r.left * s, y, r.right * s, y, paint)
                    }
                }
            }
            is Ink -> if (a.pts.size > 1) {
                paint.style = Paint.Style.STROKE; paint.color = a.color; paint.strokeWidth = a.width * s
                paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                val path = Path()
                path.moveTo(a.pts[0].x * s, a.pts[0].y * s)
                for (i in 1 until a.pts.size) path.lineTo(a.pts[i].x * s, a.pts[i].y * s)
                canvas.drawPath(path, paint)
            }
            is TextBox -> drawText(canvas, a.text, a.x, a.y, a.size, a.color, s)
            is CoverText -> {
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                for (r in a.rects) canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, paint)
                a.rects.firstOrNull()?.let { drawText(canvas, a.text, it.left, it.top, a.size, a.color, s) }
            }
            is ImageStamp -> canvas.drawBitmap(a.bmp, null, RectF(a.x * s, a.y * s, (a.x + a.w) * s, (a.y + a.h) * s), null)
        }
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, s: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = color
        paint.textSize = size * s
        for ((i, line) in text.split('\n').withIndex()) canvas.drawText(line, x * s, (y + size * 0.85f + i * size * 1.2f) * s, paint)
    }

    private fun boundsOf(a: PdfAnnot): RectF = when (a) {
        is Markup -> union(a.rects)
        is CoverText -> union(a.rects)
        is Ink -> RectF(a.pts.minOf { it.x } - 4, a.pts.minOf { it.y } - 4, a.pts.maxOf { it.x } + 4, a.pts.maxOf { it.y } + 4)
        is TextBox -> RectF(a.x, a.y, a.x + a.text.split('\n').maxOf { it.length } * a.size * 0.55f, a.y + a.text.split('\n').size * a.size * 1.2f)
        is ImageStamp -> RectF(a.x, a.y, a.x + a.w, a.y + a.h)
    }

    private fun union(rs: List<RectF>): RectF {
        val r = RectF(rs.first())
        for (x in rs) r.union(x)
        return r
    }

    // ------------------------------------------------------------ táctil
    fun onPageTouch(v: PdfPageView, e: MotionEvent): Boolean {
        val s = v.scale
        val x = e.x / s
        val y = e.y / s
        when (mode) {
            Mode.DRAW -> {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        strokePts = arrayListOf(PointF(x, y)); strokePage = v.pageIndex
                    }
                    MotionEvent.ACTION_MOVE -> strokePts?.add(PointF(x, y))
                    MotionEvent.ACTION_UP -> {
                        val pts = strokePts
                        strokePts = null
                        if (pts != null && pts.size >= 2) addAnnot(Ink(v.pageIndex, pts, penColor, penWidth))
                    }
                    MotionEvent.ACTION_CANCEL -> strokePts = null
                }
                v.invalidate()
                return true
            }
            Mode.SELECT -> return selectTouch(v, e, x, y)
            else -> {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false }
                    MotionEvent.ACTION_MOVE -> if (hypot(e.x - downX, e.y - downY) > slop) moved = true
                    MotionEvent.ACTION_UP -> if (!moved) onTap(v.pageIndex, x, y)
                }
                return true
            }
        }
    }

    private fun selectTouch(v: PdfPageView, e: MotionEvent, x: Float, y: Float): Boolean {
        val p = v.pageIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; dragging = 0
                val ws = wordCache[p]
                if (selLo >= 0 && selPage == p && ws != null) {
                    val s = v.scale
                    val a = ws.getOrNull(selLo)
                    val z = ws.getOrNull(min(selHi, ws.size - 1))
                    val lim = dp(28).toFloat()
                    if (a != null && hypot(e.x - a.l * s, e.y - (a.b * s + dp(6))) < lim) dragging = 1
                    else if (z != null && hypot(e.x - z.r * s, e.y - (z.b * s + dp(6))) < lim) dragging = 2
                    if (dragging != 0) { v.parent?.requestDisallowInterceptTouchEvent(true); sb.root.isVisible = false }
                }
                if (dragging == 0) {
                    val r = Runnable {
                        val wi = wordAt(p, x, y)
                        if (wi >= 0) {
                            selPage = p; selLo = wi; selHi = wi; dragging = 2
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            sb.root.isVisible = false
                            invalidateAll()
                        }
                    }
                    longRunnable = r
                    handler.postDelayed(r, 450)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > slop) { moved = true; if (dragging == 0) cancelLong() }
                if (dragging != 0 && selPage == p) {
                    val i = nearestWord(wordCache[p] ?: emptyList(), x, y)
                    if (i >= 0) {
                        if (dragging == 1) selLo = min(i, selHi) else selHi = max(i, selLo)
                        invalidateAll()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLong()
                if (dragging != 0) { dragging = 0; showSelBar() }
                else if (!moved && selLo >= 0) clearSelection()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLong()
                if (dragging != 0) { dragging = 0; showSelBar() }
            }
        }
        return true
    }

    private fun cancelLong() { longRunnable?.let { handler.removeCallbacks(it) }; longRunnable = null }

    private fun wordAt(p: Int, x: Float, y: Float): Int {
        val ws = wordCache[p] ?: return -1
        for ((i, w) in ws.withIndex()) if (x >= w.l - 3 && x <= w.r + 3 && y >= w.t - 3 && y <= w.b + 3) return i
        val n = nearestWord(ws, x, y)
        if (n < 0) return -1
        val w = ws[n]
        val dx = max(0f, max(w.l - x, x - w.r)); val dy = max(0f, max(w.t - y, y - w.b))
        return if (hypot(dx, dy) < 22f) n else -1
    }

    private fun nearestWord(ws: List<PWord>, x: Float, y: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for ((i, w) in ws.withIndex()) {
            val dx = max(0f, max(w.l - x, x - w.r)); val dy = max(0f, max(w.t - y, y - w.b))
            val d = hypot(dx, dy * 2f)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    // ------------------------------------------------------------ selección
    private fun bindSelectionBar() {
        sb.psCopy.setOnClickListener { copySelection() }
        sb.psHl.setOnClickListener { markSelection(Markup.HIGHLIGHT) }
        sb.psUl.setOnClickListener { markSelection(Markup.UNDERLINE) }
        sb.psStrike.setOnClickListener { markSelection(Markup.STRIKE) }
        sb.psReplace.setOnClickListener { replaceSelection() }
        sb.psAll.setOnClickListener {
            val p = if (selPage >= 0) selPage else currentPage()
            val ws = wordCache[p]
            if (ws.isNullOrEmpty()) toast("Esta página no tiene texto (usa OCR)")
            else { selPage = p; selLo = 0; selHi = ws.size - 1; invalidateAll(); showSelBar() }
        }
        sb.psClose.setOnClickListener { clearSelection() }
    }

    private fun showSelBar() { sb.root.isVisible = selLo >= 0 }

    private fun clearSelection() {
        selPage = -1; selLo = -1; selHi = -1; dragging = 0
        sb.root.isVisible = false
        invalidateAll()
    }

    private fun selectedWords(): List<PWord> {
        val ws = wordCache[selPage] ?: return emptyList()
        if (selLo < 0) return emptyList()
        return ws.subList(selLo, min(selHi, ws.size - 1) + 1)
    }

    private fun copySelection() {
        val t = PdfTextExtractor.textOf(selectedWords())
        if (t.isEmpty()) return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF", t))
        toast("Texto copiado")
    }

    private fun mergedRects(words: List<PWord>): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (w in words) {
            val r = w.rect()
            val c = cur
            if (c != null && abs((c.top + c.bottom) / 2 - (r.top + r.bottom) / 2) < 0.6f * (c.bottom - c.top)) c.union(r)
            else { if (c != null) out.add(c); cur = RectF(r) }
        }
        cur?.let { out.add(it) }
        return out
    }

    private fun hlColor() = if (ColorUtils.calculateLuminance(penColor) > 0.55) penColor else 0xFFFFEB3B.toInt()

    private fun markSelection(kind: Int) {
        val rects = mergedRects(selectedWords())
        if (rects.isEmpty()) return
        addAnnot(Markup(selPage, kind, rects, if (kind == Markup.HIGHLIGHT) hlColor() else penColor))
        clearSelection()
    }

    private fun replaceSelection() {
        val words = selectedWords()
        if (words.isEmpty()) return
        val rects = mergedRects(words)
        val page = selPage
        val et = EditText(ctx).apply { setText(PdfTextExtractor.textOf(words).replace('\n', ' ')) }
        val box = FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(ctx).setTitle("Reemplazar texto").setView(box)
            .setMessage("Se tapa el texto original con blanco y se escribe el nuevo encima.")
            .setPositiveButton("Aplicar") { _, _ ->
                val size = (rects.first().height() * 0.9f).coerceIn(6f, 40f)
                addAnnot(CoverText(page, rects, et.text.toString(), size, Color.BLACK))
                clearSelection()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ anotaciones
    private fun addAnnot(a: PdfAnnot) {
        session.checkpointAnnots()
        session.annots.add(a)
        invalidateAll()
        updateUndo()
    }

    private fun updateUndo() {
        tb.tpUndo.alpha = if (session.undo.isEmpty()) 0.35f else 1f
        tb.tpRedo.alpha = if (session.redo.isEmpty()) 0.35f else 1f
    }

    private fun onTap(page: Int, x: Float, y: Float) {
        when (mode) {
            Mode.TEXT -> textDialog(page, x, y)
            Mode.SIGN -> signFlow(page, x, y)
            Mode.STAMP -> stampDialog(page, x, y)
            Mode.ERASE -> {
                val a = session.annots.lastOrNull { it.page == page && boundsOf(it).contains(x, y) }
                if (a != null) {
                    session.checkpointAnnots()
                    session.annots.remove(a)
                    invalidateAll(); updateUndo()
                } else toast("No hay anotación aquí")
            }
            else -> Unit
        }
    }

    private fun textDialog(page: Int, x: Float, y: Float) {
        val et = EditText(ctx).apply { hint = "Texto"; minLines = 2; gravity = Gravity.TOP }
        val box = FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(ctx).setTitle("Añadir texto").setView(box)
            .setPositiveButton("Añadir") { _, _ ->
                val t = et.text.toString()
                if (t.isNotBlank()) addAnnot(TextBox(page, x, y - 7f, t, 14f, penColor))
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun place(page: Int, cx: Float, cy: Float, png: ByteArray, widthPts: Float) {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
        val h = widthPts * bmp.height / bmp.width
        addAnnot(ImageStamp(page, cx - widthPts / 2, cy - h / 2, widthPts, h, png, bmp))
    }

    private fun signFlow(page: Int, x: Float, y: Float) {
        val file = File(ctx.filesDir, "signature.png")
        if (file.exists()) {
            AlertDialog.Builder(ctx).setTitle("Firma")
                .setItems(arrayOf("Usar firma guardada", "Nueva firma")) { _, i ->
                    if (i == 0) place(page, x, y, file.readBytes(), 140f) else signPad(page, x, y, file)
                }.show()
        } else signPad(page, x, y, file)
    }

    private fun signPad(page: Int, x: Float, y: Float, file: File) {
        val pad = SignaturePadView(ctx)
        val box = FrameLayout(ctx).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(200)))
        }
        val dlg = AlertDialog.Builder(ctx).setTitle("Dibuja tu firma").setView(box)
            .setPositiveButton("Usar", null).setNeutralButton("Limpiar", null)
            .setNegativeButton("Cancelar", null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val bmp = pad.toBitmap()
            if (bmp == null) toast("Dibuja primero la firma")
            else {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                val bytes = out.toByteArray()
                file.writeBytes(bytes)
                place(page, x, y, bytes, 140f)
                dlg.dismiss()
            }
        }
    }

    private fun stampDialog(page: Int, x: Float, y: Float) {
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val stamps = listOf(
            Triple("APROBADO", 0xFF2E7D32.toInt(), 150f), Triple("RECHAZADO", 0xFFC62828.toInt(), 150f),
            Triple("BORRADOR", 0xFF757575.toInt(), 150f), Triple("CONFIDENCIAL", 0xFFC62828.toInt(), 170f),
            Triple("URGENTE", 0xFFC62828.toInt(), 130f), Triple(date, Color.BLACK, 110f),
            Triple("✔", 0xFF2E7D32.toInt(), 30f), Triple("✘", 0xFFC62828.toInt(), 30f)
        )
        AlertDialog.Builder(ctx).setTitle("Sello").setItems(stamps.map { it.first }.toTypedArray()) { _, i ->
            val (t, c, w) = stamps[i]
            place(page, x, y, stampPng(t, c), w)
        }.show()
    }

    private fun stampPng(text: String, color: Int): ByteArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 64f; typeface = Typeface.DEFAULT_BOLD }
        val pad = 22f
        val single = text.length <= 2
        val w = (p.measureText(text) + pad * 2).toInt()
        val h = (64f + pad * 1.6f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (!single) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 6f }
            c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), 14f, 14f, bp)
        }
        val fm = p.fontMetrics
        c.drawText(text, pad, h / 2f - (fm.ascent + fm.descent) / 2f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ barra de herramientas
    private fun bindToolbar() {
        tb.tpUndo.setOnClickListener { if (session.undoAnnot()) { invalidateAll(); updateUndo() } }
        tb.tpRedo.setOnClickListener { if (session.redoAnnot()) { invalidateAll(); updateUndo() } }
        tb.tpSelect.setOnClickListener { setMode(Mode.SELECT) }
        tb.tpDraw.setOnClickListener { setMode(Mode.DRAW) }
        tb.tpText.setOnClickListener { setMode(Mode.TEXT) }
        tb.tpSign.setOnClickListener { setMode(Mode.SIGN) }
        tb.tpStamp.setOnClickListener { setMode(Mode.STAMP) }
        tb.tpErase.setOnClickListener { setMode(Mode.ERASE) }
        tb.tpColor.setOnClickListener { colorDialog() }
        tb.tpWidth.setOnClickListener { widthMenu(it) }
        tb.tpZoomOut.setOnClickListener { zoomButton(1f / 1.3f) }
        tb.tpZoomIn.setOnClickListener { zoomButton(1.3f) }
        tb.tpNight.setOnClickListener {
            night = !night
            for (i in 0 until b.pages.childCount) (b.pages.getChildAt(i) as? PdfPageView)?.night = night
        }
        tb.tpFind.setOnClickListener { findDialog() }
        tb.tpPages.setOnClickListener { pagesMenu(it) }
        tb.tpForm.setOnClickListener { formDialog() }
        tb.tpTextMenu.setOnClickListener { textMenu(it) }
        updateUndo()
    }

    private fun setMode(m: Mode) {
        mode = m
        if (m != Mode.SELECT) clearSelection()
        val map = listOf(tb.tpSelect to Mode.SELECT, tb.tpDraw to Mode.DRAW, tb.tpText to Mode.TEXT,
            tb.tpSign to Mode.SIGN, tb.tpStamp to Mode.STAMP, tb.tpErase to Mode.ERASE)
        for ((btn, md) in map) btn.backgroundTintList = ColorStateList.valueOf(if (md == m) 0x553B82F6 else Color.TRANSPARENT)
        when (m) {
            Mode.DRAW -> toast("Dibuja con el dedo (el desplazamiento se desactiva)")
            Mode.TEXT -> toast("Toca donde quieras escribir")
            Mode.SIGN -> toast("Toca donde quieras firmar")
            Mode.STAMP -> toast("Toca donde quieras el sello")
            Mode.ERASE -> toast("Toca una anotación para borrarla")
            else -> Unit
        }
    }

    private fun colorDialog() {
        val colors = listOf(0xFFE53935, 0xFFFF9800, 0xFFFFEB3B, 0xFF43A047, 0xFF1E88E5, 0xFF5E35B1, 0xFF000000, 0xFF757575)
            .map { it.toInt() }
        val grid = GridLayout(ctx).apply { columnCount = 4; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var dlg: AlertDialog
        for (c in colors) {
            val v = View(ctx)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), 0x55888888) }
            v.layoutParams = GridLayout.LayoutParams().apply { width = dp(44); height = dp(44); setMargins(dp(6), dp(6), dp(6), dp(6)) }
            v.setOnClickListener { penColor = c; tb.tpColor.setTextColor(c); dlg.dismiss() }
            grid.addView(v)
        }
        dlg = AlertDialog.Builder(ctx).setTitle("Color de anotación").setView(grid).create()
        dlg.show()
    }

    private fun widthMenu(anchor: View) {
        val opts = listOf("Fino" to 1.5f, "Medio" to 3f, "Grueso" to 6f)
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener { penWidth = opts[it.itemId].second; true }
        m.show()
    }

    // ------------------------------------------------------------ páginas
    private fun pageOp(op: suspend () -> Unit) {
        scope.launch {
            try {
                session.commit()
                op()
                afterFileChange()
            } catch (e: Exception) {
                toast("No se pudo completar: ${e.message ?: "error"}")
            }
        }
    }

    private suspend fun afterFileChange() {
        val keep = currentPage()
        clearSelection()
        wordCache.clear(); loading.clear(); hits.clear(); cache.evictAll(); clearDetails()
        val old = extractor
        extractor = PdfTextExtractor(session.work)
        closeLater(old)
        openRenderer()
        adapter.notifyDataSetChanged()
        b.pages.scrollToPosition(min(keep, max(0, sizes.size - 1)))
        updateUndo()
    }

    /** El fragmento lo llama tras guardar: las anotaciones ya están dentro del archivo. */
    fun dirty() = session.annots.isNotEmpty() || session.fileOps
    fun markSaved() {}
    fun reloadView() { scope.launch { afterFileChange() } }

    fun shortcut(code: Int): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_Z -> { if (session.undoAnnot()) { invalidateAll(); updateUndo() }; true }
        android.view.KeyEvent.KEYCODE_Y -> { if (session.redoAnnot()) { invalidateAll(); updateUndo() }; true }
        android.view.KeyEvent.KEYCODE_F -> { findDialog(); true }
        else -> false
    }
    fun onSaved() { session.fileOps = false; scope.launch { afterFileChange() } }

    private fun pagesMenu(anchor: View) {
        val opts = listOf("Rotar página ↻" to "rr", "Rotar página ↺" to "rl", "Eliminar página" to "del",
            "Mover página hacia el inicio" to "up", "Mover página hacia el final" to "down",
            "Miniaturas y reordenar…" to "thumbs", "Marcadores…" to "outline",
            "Insertar página en blanco después" to "blank", "Añadir otro PDF al final…" to "merge",
            "Extraer páginas a un PDF nuevo…" to "extract", "Deshacer última operación de páginas" to "undo")
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            val p = currentPage()
            when (opts[it.itemId].second) {
                "rr" -> pageOp { session.rotate(p, 90) }
                "rl" -> pageOp { session.rotate(p, -90) }
                "del" -> AlertDialog.Builder(ctx).setTitle("Eliminar página ${p + 1}")
                    .setMessage("¿Seguro? Podrás deshacerlo desde este mismo menú.")
                    .setPositiveButton("Eliminar") { _, _ -> pageOp { session.delete(p) } }
                    .setNegativeButton("Cancelar", null).show()
                "up" -> if (p > 0) pageOp { session.move(p, p - 1) }
                "down" -> if (p < sizes.size - 1) pageOp { session.move(p, p + 2) }
                "blank" -> pageOp { session.insertBlank(p) }
                "merge" -> pickPdf()
                "thumbs" -> thumbsDialog()
                "outline" -> outlineDialog()
                "extract" -> extractDialog()
                "undo" -> scope.launch {
                    if (session.undoFileOp()) afterFileChange() else toast("No hay operaciones que deshacer")
                }
            }
            true
        }
        m.show()
    }

    // ------------------------------------------------------------ miniaturas y marcadores
    private suspend fun renderThumb(i: Int): Bitmap? = renderMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val r = renderer ?: return@withContext null
                r.openPage(i).use { pg ->
                    val w = 240
                    val h = (w.toFloat() / pg.width * pg.height).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun goToPage(p: Int) {
        (b.pages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, 0)
    }

    private fun thumbsDialog() {
        val rv = RecyclerView(ctx)
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 3)
        lateinit var dlg: AlertDialog
        rv.adapter = ThumbAdapter(sizes.size, scope, { renderThumb(it) }, { p -> dlg.dismiss(); goToPage(p) }) { p, anchor ->
            val opts = listOf("Ir a la página" to "go", "Mover al inicio" to "first", "Mover una posición antes" to "up",
                "Mover una posición después" to "down", "Mover al final" to "last", "Rotar ↻" to "rot", "Eliminar" to "del")
            val m = PopupMenu(ctx, anchor)
            opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
            m.setOnMenuItemClickListener {
                val n = sizes.size
                when (opts[it.itemId].second) {
                    "go" -> { dlg.dismiss(); goToPage(p) }
                    "first" -> if (p > 0) { dlg.dismiss(); pageOp { session.move(p, 0) } }
                    "up" -> if (p > 0) { dlg.dismiss(); pageOp { session.move(p, p - 1) } }
                    "down" -> if (p < n - 1) { dlg.dismiss(); pageOp { session.move(p, p + 2) } }
                    "last" -> if (p < n - 1) { dlg.dismiss(); pageOp { session.move(p, n) } }
                    "rot" -> { dlg.dismiss(); pageOp { session.rotate(p, 90) } }
                    "del" -> { dlg.dismiss(); pageOp { session.delete(p) } }
                }
                true
            }
            m.show()
        }
        dlg = AlertDialog.Builder(ctx).setTitle("Páginas (toca para ir, mantén pulsado para mover)")
            .setView(rv).setNegativeButton("Cerrar", null).create()
        dlg.show()
    }

    private fun outlineDialog() {
        scope.launch {
            val items = try { session.outline() } catch (e: Exception) { emptyList() }
            if (items.isEmpty()) { toast("Este PDF no tiene marcadores"); return@launch }
            val labels = items.map { "    ".repeat(it.third) + it.first.ifBlank { "(sin título)" } + if (it.second >= 0) "  · ${it.second + 1}" else "" }
                .toTypedArray()
            AlertDialog.Builder(ctx).setTitle("Marcadores").setItems(labels) { _, i ->
                if (items[i].second >= 0) goToPage(items[i].second) else toast("Este marcador no apunta a una página")
            }.setNegativeButton("Cerrar", null).show()
        }
    }

    fun mergeFrom(uri: Uri) {
        scope.launch {
            try {
                val f = File(ctx.cacheDir, "merge_${System.nanoTime()}.pdf")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
                session.commit()
                session.append(f)
                f.delete()
                afterFileChange()
                toast("PDF añadido al final")
            } catch (e: Exception) {
                toast("No se pudo añadir: ${e.message ?: "archivo no válido"}")
            }
        }
    }

    private fun extractDialog() {
        val et = EditText(ctx).apply { hint = "Ej.: 1-3,5"; setText("${currentPage() + 1}") }
        val box = FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(ctx).setTitle("Páginas a extraer (1–${sizes.size})").setView(box)
            .setPositiveButton("Continuar") { _, _ ->
                val pages = parsePages(et.text.toString(), sizes.size)
                if (pages.isEmpty()) toast("Rango no válido")
                else {
                    pendingPages = pages
                    scope.launch {
                        try { session.commit(); requestTarget("extract", "application/pdf", "extracto.pdf") }
                        catch (e: Exception) { toast("No se pudo preparar: ${e.message}") }
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun parsePages(s: String, n: Int): List<Int> {
        val out = sortedSetOf<Int>()
        for (part in s.split(',')) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val m = t.split('-')
            val a = m[0].trim().toIntOrNull() ?: continue
            val z = m.getOrNull(1)?.trim()?.toIntOrNull() ?: a
            for (i in min(a, z)..max(a, z)) if (i in 1..n) out.add(i - 1)
        }
        return out.toList()
    }

    /** Resultado del selector de destino (extraer páginas / exportar a Word). */
    fun onTarget(kind: String, uri: Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        if (kind == "extract") session.extract(pendingPages, out) else out.write(pendingDocx)
                    }
                }
                toast("Guardado")
            } catch (e: Exception) {
                toast("No se pudo guardar: ${e.message ?: "error"}")
            }
        }
    }

    // ------------------------------------------------------------ formulario
    private fun formDialog() {
        scope.launch {
            val fields = try { session.commit(); session.formFields() } catch (e: Exception) { emptyList() }
            if (fields.isEmpty()) { toast("Este PDF no tiene campos de formulario"); return@launch }
            val sv = ScrollView(ctx)
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
            val getters = ArrayList<() -> Pair<String, String>>()
            for (f in fields) {
                box.addView(TextView(ctx).apply { text = f.name.substringAfterLast('.'); setPadding(0, dp(10), 0, 0) })
                when (f.type) {
                    FormField.TEXT -> {
                        val et = EditText(ctx).apply { setText(f.value) }
                        box.addView(et); getters.add { f.name to et.text.toString() }
                    }
                    FormField.CHECK -> {
                        val cb = CheckBox(ctx).apply { text = "Marcado"; isChecked = f.value == "true" }
                        box.addView(cb); getters.add { f.name to if (cb.isChecked) "true" else "false" }
                    }
                    else -> {
                        val opts = if (f.type == FormField.CHOICE) listOf("") + f.options else f.options
                        val sp = Spinner(ctx)
                        sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opts)
                        sp.setSelection(max(0, opts.indexOf(f.value)))
                        box.addView(sp); getters.add { f.name to ((sp.selectedItem as? String) ?: "") }
                    }
                }
            }
            sv.addView(box)
            AlertDialog.Builder(ctx).setTitle("Rellenar formulario").setView(sv)
                .setPositiveButton("Aplicar") { _, _ ->
                    val values = getters.associate { it() }
                    pageOp { session.fillForm(values) }
                }.setNegativeButton("Cancelar", null).show()
        }
    }

    // ------------------------------------------------------------ texto / OCR / búsqueda
    private fun textMenu(anchor: View) {
        val opts = listOf("Copiar texto de la página" to "copy", "OCR: reconocer esta página" to "ocr1",
            "OCR: reconocer todo el documento" to "ocrAll", "Exportar texto a Word (DOCX)…" to "docx")
        val m = PopupMenu(ctx, anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            when (opts[it.itemId].second) {
                "copy" -> copyPage()
                "ocr1" -> ocrPages(listOf(currentPage()))
                "ocrAll" -> ocrPages((0 until sizes.size).toList())
                "docx" -> exportDocx()
            }
            true
        }
        m.show()
    }

    private fun copyPage() {
        val p = currentPage()
        scope.launch {
            val t = PdfTextExtractor.textOf(extractor.allWords(p))
            if (t.isEmpty()) toast("Esta página no tiene texto: usa OCR")
            else {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF", t))
                toast("Texto de la página copiado")
            }
        }
    }

    private fun progressDialog(title: String): Pair<AlertDialog, TextView> {
        val tv = TextView(ctx).apply { setPadding(dp(24), dp(16), dp(24), dp(16)); text = "Preparando…" }
        val dlg = AlertDialog.Builder(ctx).setTitle(title).setView(tv).setCancelable(false).create()
        dlg.show()
        return dlg to tv
    }

    private fun ocrPages(pages: List<Int>) {
        scope.launch {
            val (dlg, tv) = progressDialog("Reconociendo texto (OCR)")
            var done = 0
            try {
                for ((k, p) in pages.withIndex()) {
                    tv.text = "Página ${k + 1} de ${pages.size}"
                    if (extractor.nativeWords(p).isEmpty()) {
                        val w = extractor.ocrPage(p)
                        wordCache[p] = w
                        done++
                    }
                }
            } finally {
                dlg.dismiss()
            }
            invalidateAll()
            toast(if (done == 0) "Las páginas ya tienen texto" else "OCR completado en $done página(s)")
        }
    }

    private fun exportDocx() {
        scope.launch {
            val (dlg, tv) = progressDialog("Preparando documento Word")
            try {
                val pages = ArrayList<List<String>>()
                for (p in 0 until sizes.size) {
                    tv.text = "Página ${p + 1} de ${sizes.size}"
                    var w = extractor.allWords(p)
                    if (w.isEmpty()) { w = extractor.ocrPage(p); wordCache[p] = w }
                    pages.add(PdfTextExtractor.paragraphs(w))
                }
                pendingDocx = DocxBuilder.build(pages)
            } catch (e: Exception) {
                dlg.dismiss(); toast("No se pudo preparar: ${e.message}"); return@launch
            }
            dlg.dismiss()
            requestTarget("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "documento.docx")
        }
    }

    private fun findDialog() {
        val et = EditText(ctx).apply { hint = "Buscar en el PDF"; setSingleLine() }
        val box = FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        val dlg = AlertDialog.Builder(ctx).setTitle("Buscar").setView(box)
            .setPositiveButton("Siguiente", null).setNegativeButton("Cerrar", null).create()
        dlg.show()
        dlg.window?.setDimAmount(0f)
        dlg.window?.setGravity(Gravity.BOTTOM)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { findNext(et.text.toString()) }
        findPage = currentPage(); findFrom = 0
    }

    private fun findNext(q: String) {
        if (q.isBlank()) return
        scope.launch {
            val n = sizes.size
            if (n == 0) return@launch
            for (k in 0..n) {
                val p = (findPage + k) % n
                val words = extractor.allWords(p)
                val text = StringBuilder()
                val starts = IntArray(words.size)
                for (i in words.indices) { starts[i] = text.length; text.append(words[i].text).append(' ') }
                val from = if (k == 0) findFrom else 0
                val idx = text.toString().indexOf(q, from, ignoreCase = true)
                if (idx >= 0) {
                    val end = idx + q.length - 1
                    val i1 = words.indices.lastOrNull { starts[it] <= idx } ?: 0
                    val i2 = words.indices.lastOrNull { starts[it] <= end } ?: i1
                    hits.clear()
                    hits[p] = words.subList(i1, i2 + 1).map { it.rect() }
                    findPage = p; findFrom = idx + 1
                    (b.pages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, 0)
                    invalidateAll()
                    return@launch
                }
            }
            toast("No se encontró")
            hits.clear(); invalidateAll()
            findFrom = 0
        }
    }

    // ------------------------------------------------------------ ciclo de vida
    private fun closeLater(e: PdfTextExtractor) {
        Thread { kotlinx.coroutines.runBlocking { e.close() } }.start()
    }

    fun close() {
        detailJob?.cancel()
        closeRenderer()
        closeLater(extractor)
        b.pages.adapter = null
    }
}

/** Cuadrícula de miniaturas de páginas (se renderizan al mostrarse). */
private class ThumbAdapter(
    private val count: Int,
    private val scope: CoroutineScope,
    private val render: suspend (Int) -> Bitmap?,
    private val onClick: (Int) -> Unit,
    private val onLong: (Int, View) -> Unit
) : RecyclerView.Adapter<ThumbAdapter.VH>() {

    class VH(val root: LinearLayout, val img: android.widget.ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = count

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        val img = android.widget.ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (150 * d).toInt())
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        val label = TextView(ctx).apply { gravity = Gravity.CENTER; textSize = 12f }
        root.addView(img); root.addView(label)
        return VH(root, img, label)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.label.text = "${position + 1}"
        h.img.setImageBitmap(null)
        h.img.tag = position
        scope.launch {
            val bmp = render(position)
            if (h.img.tag == position && bmp != null) h.img.setImageBitmap(bmp)
        }
        h.root.setOnClickListener { onClick(position) }
        h.root.setOnLongClickListener { onLong(position, it); true }
    }
}
