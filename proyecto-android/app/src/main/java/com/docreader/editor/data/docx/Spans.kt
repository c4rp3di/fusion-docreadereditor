package com.docreader.editor.data.docx

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

// Spans propios: así se distinguen de los que añaden el teclado/IME.
class BoldSpan : StyleSpan(Typeface.BOLD)
class ItalicSpan : StyleSpan(Typeface.ITALIC)
class UlSpan : UnderlineSpan()
class StrSpan : StrikethroughSpan()
class FgSpan(color: Int) : ForegroundColorSpan(color)
class BgSpan(color: Int) : BackgroundColorSpan(color)

/** Factor de zoom del editor de Word (afecta al texto con tamaño propio). */
object DocZoom { @Volatile var factor = 1f }

/** Tamaño en puntos de Word. */
class SizeSpan(val pt: Float) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.textSize = pt * 1.45f * tp.density * DocZoom.factor }
    override fun updateMeasureState(tp: TextPaint) { tp.textSize = pt * 1.45f * tp.density * DocZoom.factor }
}

class FontSpan(val name: String) : MetricAffectingSpan() {
    private fun apply(tp: TextPaint) {
        val style = tp.typeface?.style ?: 0
        tp.typeface = Typeface.create(FontMap.generic(name), style)
    }
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
}

/** Propiedades de run sin representación visual (superíndice, estilo de carácter...) que se conservan. */
class ExtraRPr(val children: List<Pair<String, String>>)

object FontMap {
    val fonts = listOf(
        "Calibri", "Arial", "Times New Roman", "Courier New", "Georgia", "Verdana",
        "Cambria", "Consolas", "Tahoma", "Trebuchet MS", "Comic Sans MS", "Garamond"
    )

    fun generic(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("courier") || n.contains("consolas") || n.contains("mono") -> "monospace"
            n.contains("times") || n.contains("georgia") || n.contains("cambria") ||
                n.contains("garamond") || n.contains("serif") && !n.contains("sans") -> "serif"
            else -> "sans-serif"
        }
    }
}

object Hl {
    val names = linkedMapOf(
        "yellow" to 0xFFFFFF00.toInt(), "green" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(), "blue" to 0xFF0000FF.toInt(), "red" to 0xFFFF0000.toInt(),
        "darkBlue" to 0xFF00008B.toInt(), "darkCyan" to 0xFF008B8B.toInt(), "darkGreen" to 0xFF006400.toInt(),
        "darkMagenta" to 0xFF8B008B.toInt(), "darkRed" to 0xFF8B0000.toInt(), "darkYellow" to 0xFF808000.toInt(),
        "darkGray" to 0xFF808080.toInt(), "lightGray" to 0xFFD3D3D3.toInt(), "black" to 0xFF000000.toInt()
    )
    val palette = listOf("yellow", "green", "cyan", "magenta", "lightGray", "red", "blue")
    fun color(n: String): Int = names[n] ?: 0xFFFFFF00.toInt()
    fun name(c: Int): String = names.entries.firstOrNull { it.value == c }?.key ?: "yellow"
}

object Fmt {
    const val BOLD = 1
    const val ITALIC = 2
    const val UNDER = 3
    const val STRIKE = 4
    const val SIZE = 5
    const val COLOR = 6
    const val HL = 7
    const val FONT = 8
    val ALL = listOf(BOLD, ITALIC, UNDER, STRIKE, SIZE, COLOR, HL, FONT)

    const val EI = Spanned.SPAN_EXCLUSIVE_INCLUSIVE
    const val EE = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    const val II = Spanned.SPAN_INCLUSIVE_INCLUSIVE

    fun kind(s: Any?): Int = when (s) {
        is BoldSpan -> BOLD
        is ItalicSpan -> ITALIC
        is UlSpan -> UNDER
        is StrSpan -> STRIKE
        is SizeSpan -> SIZE
        is FgSpan -> COLOR
        is BgSpan -> HL
        is FontSpan -> FONT
        else -> 0
    }

    fun make(kind: Int, value: Any?): Any = when (kind) {
        BOLD -> BoldSpan()
        ITALIC -> ItalicSpan()
        UNDER -> UlSpan()
        STRIKE -> StrSpan()
        SIZE -> SizeSpan(value as Float)
        COLOR -> FgSpan(value as Int)
        HL -> BgSpan(value as Int)
        else -> FontSpan(value as String)
    }

    fun clone(s: Any): Any = when (s) {
        is BoldSpan -> BoldSpan()
        is ItalicSpan -> ItalicSpan()
        is UlSpan -> UlSpan()
        is StrSpan -> StrSpan()
        is SizeSpan -> SizeSpan(s.pt)
        is FgSpan -> FgSpan(s.foregroundColor)
        is BgSpan -> BgSpan(s.backgroundColor)
        is FontSpan -> FontSpan(s.name)
        else -> s
    }

    /** Copia solo texto y spans propios (descarta spans internos del EditText). */
    fun copyText(src: CharSequence): SpannableStringBuilder {
        val out = SpannableStringBuilder(src.toString())
        if (src is Spanned) {
            for (s in src.getSpans(0, src.length, Any::class.java)) {
                if (kind(s) != 0 || s is ExtraRPr) {
                    out.setSpan(clone(s), src.getSpanStart(s), src.getSpanEnd(s), src.getSpanFlags(s))
                }
            }
        }
        return out
    }

    /** append que no deja que los spans con final inclusivo absorban el texto añadido. */
    fun appendSafe(t: SpannableStringBuilder, extra: CharSequence) {
        val n = t.length
        val saved = t.getSpans(0, n, Any::class.java)
            .filter { kind(it) != 0 || it is ExtraRPr }
            .map { Triple(it, t.getSpanStart(it) to t.getSpanEnd(it), t.getSpanFlags(it)) }
        t.append(extra)
        for ((sp, range, flags) in saved) t.setSpan(sp, range.first, range.second, flags)
    }

    fun spansOf(e: Spanned, kind: Int, s: Int, en: Int): List<Any> =
        e.getSpans(s, en, Any::class.java).filter { kind(it) == kind }

    fun covered(e: Spanned, s: Int, en: Int, kind: Int): Boolean {
        var pos = s
        val rs = spansOf(e, kind, s, en)
            .map { e.getSpanStart(it) to e.getSpanEnd(it) }
            .filter { it.first < it.second }
            .sortedBy { it.first }
        for ((a, b) in rs) {
            if (a > pos) return false
            if (b > pos) pos = b
            if (pos >= en) return true
        }
        return pos >= en
    }

    /** ¿El texto que se escriba en el cursor heredará este formato? */
    fun hasAtCaret(e: Spanned, s: Int, kind: Int): Boolean = spansOf(e, kind, s, s).any {
        val ss = e.getSpanStart(it)
        val se = e.getSpanEnd(it)
        val f = e.getSpanFlags(it)
        val startOk = ss < s || (ss == s && (f and 0xF0) == 0x10)
        val endOk = se > s || (se == s && (f and 0x0F) == 0x02)
        startOk && endOk
    }

    fun valueAt(e: Spanned, pos: Int, kind: Int): Any? {
        val p = if (pos > 0) pos - 1 else 0
        return spansOf(e, kind, p, pos).lastOrNull { e.getSpanStart(it) <= p && e.getSpanEnd(it) >= pos }
    }

    fun remove(e: Spannable, s: Int, en: Int, kind: Int, keepFlag: Int = EI) {
        for (sp in spansOf(e, kind, s, en)) {
            val ss = e.getSpanStart(sp)
            val se = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (ss < s) e.setSpan(clone(sp), ss, s, keepFlag)
            if (se > en) e.setSpan(clone(sp), en, se, EI)
        }
    }

    fun add(e: Spannable, s: Int, en: Int, kind: Int, value: Any?) {
        remove(e, s, en, kind)
        e.setSpan(make(kind, value), s, en, EI)
    }

    fun addCaret(e: Spannable, s: Int, kind: Int, value: Any?) {
        remove(e, s, s, kind, EE)
        e.setSpan(make(kind, value), s, s, II)
    }
}
