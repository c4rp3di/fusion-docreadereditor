package com.docreader.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/** Lienzo para dibujar una firma; devuelve un PNG transparente recortado. */
class SignaturePadView(context: Context) : View(context) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var any = false

    init { setBackgroundColor(0xFFF4F4F4.toInt()) }

    fun isEmpty() = !any
    fun clear() { path.reset(); any = false; invalidate() }

    override fun onDraw(canvas: Canvas) { canvas.drawPath(path, paint) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); path.moveTo(e.x, e.y); any = true }
            MotionEvent.ACTION_MOVE -> path.lineTo(e.x, e.y)
        }
        invalidate()
        return true
    }

    fun toBitmap(): Bitmap? {
        if (!any || width == 0) return null
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(full).drawPath(path, paint)
        val b = android.graphics.RectF()
        path.computeBounds(b, true)
        val l = (b.left - 8).toInt().coerceAtLeast(0)
        val t = (b.top - 8).toInt().coerceAtLeast(0)
        val r = (b.right + 8).toInt().coerceAtMost(width)
        val bt = (b.bottom + 8).toInt().coerceAtMost(height)
        if (r - l < 4 || bt - t < 4) return null
        return Bitmap.createBitmap(full, l, t, r - l, bt - t)
    }
}
