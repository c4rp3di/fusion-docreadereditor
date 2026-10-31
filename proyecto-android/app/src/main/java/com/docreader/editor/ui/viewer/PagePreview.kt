package com.docreader.editor.ui.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.databinding.FragmentViewerBinding

/** Filtro de color que invierte la imagen (modo noche). */
object Night {
    val paint: Paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )))
    }
}

/** Genera un PDF con las páginas (texto vectorial, tamaño real en puntos). */
fun pagesToPdf(pages: List<PreviewPage>): ByteArray {
    val d = PdfDocument()
    pages.forEachIndexed { i, p ->
        val info = PdfDocument.PageInfo.Builder(p.wPt.toInt(), p.hPt.toInt(), i + 1).create()
        val pg = d.startPage(info)
        pg.canvas.drawColor(Color.WHITE)
        p.draw(pg.canvas)
        d.finishPage(pg)
    }
    val bos = java.io.ByteArrayOutputStream()
    d.writeTo(bos)
    d.close()
    return bos.toByteArray()
}

/** Una página de papel: tamaño en puntos y cómo dibujarla (en unidades de punto). */
class PreviewPage(val wPt: Float, val hPt: Float, val draw: (Canvas) -> Unit)

class PagePreviewView(context: Context) : View(context) {
    var page: PreviewPage? = null
    var night: Boolean = false
        set(v) {
            field = v
            setLayerType(if (v) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, if (v) Night.paint else null)
            invalidate()
        }

    init {
        setBackgroundColor(Color.WHITE)
        elevation = 8f
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val p = page
        setMeasuredDimension(w, if (p == null) 1 else (w * p.hPt / p.wPt).toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        val s = width / p.wPt
        canvas.save()
        canvas.scale(s, s)
        p.draw(canvas)
        canvas.restore()
    }
}

class PreviewAdapter(private val pages: List<PreviewPage>) : RecyclerView.Adapter<PreviewAdapter.VH>() {
    var night = false
    class VH(val v: PagePreviewView) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val v = PagePreviewView(parent.context)
        v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt()) }
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.v.page = pages[position]
        h.v.night = night
        h.v.requestLayout()
        h.v.invalidate()
    }
}

/** Muestra una lista de páginas en la zona de vista previa con zoom fluido (pellizco, doble toque y botones). */
class PagePreviewController(
    private val ctx: Context,
    private val b: FragmentViewerBinding,
    private val onExport: (List<PreviewPage>) -> Unit,
    private val onClose: () -> Unit
) {
    private var zoom = 1f
    private var pages: List<PreviewPage> = emptyList()
    private var adapter: PreviewAdapter? = null

    init {
        b.previewList.layoutManager = LinearLayoutManager(ctx)
        b.pvZoomIn.setOnClickListener { button(1.4f) }
        b.pvZoomOut.setOnClickListener { button(1f / 1.4f) }
        b.pvClose.setOnClickListener { hide() }
        b.pvNight.setOnClickListener { adapter?.let { it.night = !it.night; it.notifyDataSetChanged() } }
        b.pvExport.setOnClickListener { onExport(pages) }
        PinchZoom(b.previewList, 1f, 5f, { zoom }, { if (zoom < 1.5f) 2.5f else 1f }, ::commit)
    }

    fun show(title: String, pages: List<PreviewPage>) {
        this.pages = pages
        b.pvTitle.text = "$title · ${pages.size} pág."
        zoom = 1f
        adapter = PreviewAdapter(pages)
        b.previewList.adapter = adapter
        applyWidth()
        b.previewBar.isVisible = true
        b.pageScroll.isVisible = true
    }

    fun hide() {
        b.previewBar.isVisible = false
        b.pageScroll.isVisible = false
        b.previewList.adapter = null
        onClose()
    }

    private fun applyWidth() {
        b.previewList.layoutParams = b.previewList.layoutParams.apply {
            width = (ctx.resources.displayMetrics.widthPixels * zoom).toInt()
        }
    }

    private fun button(f: Float) {
        val nz = (zoom * f).coerceIn(1f, 5f)
        if (kotlin.math.abs(nz - zoom) < 0.01f) return
        val cx = b.pageScroll.scrollX + b.pageScroll.width / 2f
        val cy = b.previewList.height / 2f
        commit(nz, nz / zoom, cx, cy, cx, cy)
    }

    private fun commit(nz: Float, r: Float, ax: Float, ay: Float, fx: Float, fy: Float) {
        val sx = b.pageScroll.scrollX
        val off = b.previewList.computeVerticalScrollOffset()
        zoom = nz
        applyWidth()
        adapter?.notifyDataSetChanged()
        b.pageScroll.doOnNextLayout { b.pageScroll.scrollTo(kotlin.math.max(0, (ax * r - (fx - sx)).toInt()), 0) }
        b.previewList.doOnNextLayout { b.previewList.scrollBy(0, ((off + ay) * r - fy - off).toInt()) }
    }
}
