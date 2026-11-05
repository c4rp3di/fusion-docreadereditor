package com.docreader.editor.data.xlsx

import kotlin.math.floor
import kotlin.math.max

/** Tamaños de columnas y filas en «píxeles de Excel» (96 ppp). */
object SheetGeometry {
    private val widthRe = Regex("width=\"([\\d.]+)\"")
    private val htRe = Regex("ht=\"([\\d.]+)\"")

    fun colChars(sh: SheetData, c: Int): Float {
        val cd = sh.cols.firstOrNull { c in it.min..it.max }
        if (cd != null) {
            if (cd.attrs.contains("hidden=\"1\"") || cd.attrs.contains("hidden=\"true\"")) return 0f
            widthRe.find(cd.attrs)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        }
        return sh.defColChars
    }

    fun colPx(sh: SheetData, c: Int): Float {
        val ch = colChars(sh, c)
        return if (ch <= 0f) 0f else floor(ch * 7f + 5f)
    }

    fun rowPx(sh: SheetData, r: Int): Float {
        val a = sh.rowAttrs[r]
        if (a != null) {
            if (a.contains("hidden=\"1\"") || a.contains("hidden=\"true\"")) return 0f
            htRe.find(a)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it * 4f / 3f }
        }
        return sh.defRowPt * 4f / 3f
    }

    fun charsFromPx(px: Float): Float = max(1f, (px - 5f) / 7f)
}
