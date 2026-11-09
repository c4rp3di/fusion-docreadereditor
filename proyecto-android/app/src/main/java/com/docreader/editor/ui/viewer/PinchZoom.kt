package com.docreader.editor.ui.viewer

import android.animation.ValueAnimator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Zoom por pellizco fluido: durante el gesto escala la vista con la GPU (sin recalcular nada) y al soltar
 * entrega el zoom final para que el contenido se vuelva a maquetar/renderizar. Opcional: doble toque.
 * onCommit(zoomNuevo, ratio, anclaX, anclaY, focoX, focoY): ancla = punto del contenido al empezar, foco = dónde acaba.
 */
class PinchZoom(
    private val rv: RecyclerView,
    private val minZoom: Float,
    private val maxZoom: Float,
    private val current: () -> Float,
    private val doubleTapTarget: (() -> Float?)?,
    private val onCommit: (Float, Float, Float, Float, Float, Float) -> Unit
) {
    private var live = 1f
    private var ax = 0f
    private var ay = 0f

    private val scale = ScaleGestureDetector(rv.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            live = 1f
            ax = d.focusX; ay = d.focusY
            rv.pivotX = ax; rv.pivotY = ay
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val z = current()
            live = (live * d.scaleFactor).coerceIn(minZoom / z, maxZoom / z)
            rv.scaleX = live; rv.scaleY = live
            rv.translationX = d.focusX - ax
            rv.translationY = d.focusY - ay
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            val ratio = live
            val fx = d.focusX; val fy = d.focusY
            live = 1f
            rv.scaleX = 1f; rv.scaleY = 1f; rv.translationX = 0f; rv.translationY = 0f
            if (abs(ratio - 1f) > 0.01f) {
                onCommit((current() * ratio).coerceIn(minZoom, maxZoom), ratio, ax, ay, fx, fy)
            }
        }
    })

    private val tap = GestureDetector(rv.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val t = doubleTapTarget?.invoke() ?: return false
            animateTo(t, e.x, e.y)
            return true
        }
    })

    /** Zoom animado (doble toque y botones): escala la vista con la GPU y al terminar entrega el zoom final. */
    fun animateTo(target: Float, ax: Float, ay: Float) {
        val z = current()
        val t = target.coerceIn(minZoom, maxZoom)
        if (abs(t - z) < 0.01f) return
        val ratio = t / z
        rv.pivotX = ax
        rv.pivotY = ay
        ValueAnimator.ofFloat(1f, ratio).apply {
            duration = 170
            addUpdateListener { rv.scaleX = it.animatedValue as Float; rv.scaleY = it.animatedValue as Float }
            doOnEnd {
                rv.scaleX = 1f; rv.scaleY = 1f; rv.translationX = 0f; rv.translationY = 0f
                onCommit(t, ratio, ax, ay, ax, ay)
            }
            start()
        }
    }

    private fun feed(e: MotionEvent) {
        // Los eventos llegan en coordenadas ya afectadas por el zoom en vivo: se devuelven a las del padre
        // para que el detector no se realimente.
        val c = MotionEvent.obtain(e)
        c.transform(rv.matrix)
        scale.onTouchEvent(c)
        if (doubleTapTarget != null) tap.onTouchEvent(c)
        c.recycle()
    }

    init {
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                feed(e)
                return scale.isInProgress
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { feed(e) }
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }
}
