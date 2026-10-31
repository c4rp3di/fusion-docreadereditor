package com.docreader.editor.ui.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import kotlin.math.max

/**
 * HorizontalScrollView que respeta el ancho (layout_width en px) de su hijo.
 * El de Android lo ignora y mide siempre a "sin límite", por eso el zoom no ensanchaba el contenido.
 */
class WideScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs) {

    override fun measureChildWithMargins(
        child: View, parentWidthMeasureSpec: Int, widthUsed: Int, parentHeightMeasureSpec: Int, heightUsed: Int
    ) {
        val lp = child.layoutParams as MarginLayoutParams
        val hSpec = getChildMeasureSpec(
            parentHeightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height
        )
        val w = if (lp.width > 0) lp.width
        else max(0, MeasureSpec.getSize(parentWidthMeasureSpec) - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin)
        child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), hSpec)
    }
}
