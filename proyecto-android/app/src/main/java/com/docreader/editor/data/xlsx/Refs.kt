package com.docreader.editor.data.xlsx

object Refs {
    val refRe = Regex("(?<![\\w.])((?:'[^']+'|[A-Za-z_][\\w.]*)!)?(\\$?)([A-Za-z]{1,3})(\\$?)(\\d+)(?![\\w(])")

    fun colToIdx(s: String): Int {
        var c = 0
        for (ch in s) c = c * 26 + (ch.uppercaseChar() - 'A' + 1)
        return c - 1
    }

    fun idxToCol(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { n--; sb.append('A' + n % 26); n /= 26 }
        return sb.reverse().toString()
    }

    fun addr(r: Int, c: Int) = idxToCol(c) + (r + 1)

    fun sheetOf(prefix: String?): String? =
        prefix?.removeSuffix("!")?.let { if (it.startsWith("'")) it.substring(1, it.length - 1) else it }

    /** Aplica fn a cada referencia A1 (fuera de textos entre comillas). fn recibe (hoja|null, col, fila) y devuelve (col, fila) nuevos o null. */
    fun transform(formula: String, fn: (String?, Int, Int, Boolean, Boolean) -> Pair<Int, Int>?): String {
        val out = StringBuilder()
        var i = 0
        while (i < formula.length) {
            if (formula[i] == '"') {
                var j = i + 1
                while (j < formula.length) {
                    if (formula[j] == '"') { if (j + 1 < formula.length && formula[j + 1] == '"') j += 2 else break } else j++
                }
                val e = minOf(j + 1, formula.length)
                out.append(formula, i, e)
                i = e
            } else {
                var j = i
                while (j < formula.length && formula[j] != '"') j++
                out.append(refRe.replace(formula.substring(i, j)) { m ->
                    val colAbs = m.groupValues[2] == "$"
                    val rowAbs = m.groupValues[4] == "$"
                    val col = colToIdx(m.groupValues[3])
                    val row = m.groupValues[5].toInt() - 1
                    val res = fn(sheetOf(m.groupValues[1].ifEmpty { null }), col, row, colAbs, rowAbs)
                    if (res == null) m.value
                    else if (res.first < 0 || res.second < 0) "#REF!"
                    else m.groupValues[1] + (if (colAbs) "$" else "") + idxToCol(res.first) +
                        (if (rowAbs) "$" else "") + (res.second + 1)
                })
                i = j
            }
        }
        return out.toString()
    }

    /** Copiar/pegar: desplaza las referencias relativas. */
    fun translate(formula: String, dr: Int, dc: Int): String =
        transform(formula) { _, col, row, colAbs, rowAbs ->
            (if (colAbs) col else col + dc) to (if (rowAbs) row else row + dr)
        }

    fun renameSheet(formula: String, old: String, new: String): String {
        val re = Regex("(?<![\\w.])(?:'${Regex.escape(old)}'|${Regex.escape(old)})!", RegexOption.IGNORE_CASE)
        val q = if (Regex("^[A-Za-z_][\\w.]*$").matches(new)) new else "'$new'"
        return re.replace(formula) { "$q!" }
    }
}
