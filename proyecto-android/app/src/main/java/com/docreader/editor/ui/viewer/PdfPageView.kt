package com.docreader.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Una página: bitmap renderizado + capa de anotaciones/selección que dibuja el controlador. */
class PdfPageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var bitmap: Bitmap? = null
    var pageW = 595f
    var pageH = 842f
    var pageIndex = -1
    var ctl: PdfEditorController? = null
    var night: Boolean = false
        set(v) {
            field = v
            setLayerType(if (v) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, if (v) Night.paint else null)
            invalidate()
        }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    val scale: Float get() = if (pageW > 0) width / pageW else 1f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * pageH / pageW).toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val bmp = bitmap
        if (bmp != null) canvas.drawBitmap(bmp, null, dst, paint) else canvas.drawColor(Color.WHITE)
        ctl?.drawOverlay(canvas, this)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = ctl?.onPageTouch(this, e) ?: false
}
