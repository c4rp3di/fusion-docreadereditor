package com.docreader.editor.data.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDPage

class PdfPasswordException : Exception("PDF protegido con contraseña")

/** Palabra con su caja en puntos de la página tal como se ve (origen arriba-izquierda). */
class PWord(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, var endsLine: Boolean = false) {
    fun rect() = RectF(l, t, r, b)
}

/** Anotaciones pendientes: se muestran encima de la página y se escriben en el PDF al guardar. */
sealed class PdfAnnot(val page: Int)

class Markup(page: Int, val kind: Int, val rects: List<RectF>, val color: Int) : PdfAnnot(page) {
    companion object { const val HIGHLIGHT = 0; const val UNDERLINE = 1; const val STRIKE = 2 }
}

class Ink(page: Int, val pts: List<PointF>, val color: Int, val width: Float) : PdfAnnot(page)

class TextBox(page: Int, val x: Float, val y: Float, val text: String, val size: Float, val color: Int) : PdfAnnot(page)

class ImageStamp(page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val png: ByteArray, val bmp: Bitmap) : PdfAnnot(page)

/** Tapar texto con blanco y escribir otro encima. */
class CoverText(page: Int, val rects: List<RectF>, val text: String, val size: Float, val color: Int) : PdfAnnot(page)

/** Convierte puntos de la página mostrada (rotada, origen arriba-izquierda) a espacio de usuario del PDF. */
class PageGeom(page: PDPage) {
    private val crop = page.cropBox
    val rot = ((page.rotation % 360) + 360) % 360
    private val llx = crop.lowerLeftX
    private val lly = crop.lowerLeftY
    private val w = crop.width
    private val h = crop.height

    fun x(dx: Float, dy: Float): Float = when (rot) {
        0 -> llx + dx
        90 -> llx + dy
        180 -> llx + w - dx
        else -> llx + w - dy
    }

    fun y(dx: Float, dy: Float): Float = when (rot) {
        0 -> lly + h - dy
        90 -> lly + dx
        180 -> lly + dy
        else -> lly + h - dx
    }
}

class FormField(
    val name: String,
    val type: Int,                // 0 texto, 1 casilla, 2 radio, 3 lista
    val value: String,
    val options: List<String>
) {
    companion object { const val TEXT = 0; const val CHECK = 1; const val RADIO = 2; const val CHOICE = 3 }
}
