package com.docreader.editor.data.xlsx

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object NumFmt {
    val builtin = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%",
        11 to "0.00E+00", 14 to "m/d/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "m/d/yyyy h:mm",
        37 to "#,##0;(#,##0)", 38 to "#,##0;(#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;(#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 49 to "@"
    )

    fun numToStr(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "#NUM!"
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal(d).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    private fun stripped(code: String) =
        code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^\\]]*\\]"), "").replace(Regex("\\\\."), "").lowercase()

    fun isDate(code: String): Boolean {
        val c = stripped(code)
        return Regex("[ymdhs]").containsMatchIn(c) && !c.contains('0') && !c.contains('#')
    }

    fun format(v: Double, code0: String): String {
        val code = if (code0.isEmpty()) "General" else code0
        if (code.equals("General", true)) return numToStr(v)
        if (code == "@") return numToStr(v)
        if (isDate(code)) return try { formatDate(v, code) } catch (e: Exception) { numToStr(v) }
        return try { formatNumber(v, code) } catch (e: Exception) { numToStr(v) }
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (ch in code) {
            if (ch == '"') q = !q
            if (ch == ';' && !q) { out.add(sb.toString()); sb.clear() } else sb.append(ch)
        }
        out.add(sb.toString())
        return out
    }

    private fun formatNumber(v0: Double, code: String): String {
        val secs = splitSections(code)
        var v = v0
        var sec = secs[0]
        var negSign = v < 0
        if (v < 0 && secs.size > 1) { sec = secs[1]; v = -v; negSign = false }
        else if (v == 0.0 && secs.size > 2) sec = secs[2]
        var prefix = StringBuilder()
        var suffix = StringBuilder()
        val core = StringBuilder()
        var i = 0
        var seenCore = false
        fun lit(t: String) { if (seenCore) suffix.append(t) else prefix.append(t) }
        while (i < sec.length) {
            val ch = sec[i]
            when {
                ch == '"' -> { val e = sec.indexOf('"', i + 1).let { if (it < 0) sec.length else it }; lit(sec.substring(i + 1, e)); i = e }
                ch == '\\' && i + 1 < sec.length -> { lit(sec[i + 1].toString()); i++ }
                ch == '_' || ch == '*' -> i++
                ch == '[' -> {
                    val e = sec.indexOf(']', i).let { if (it < 0) sec.length - 1 else it }
                    val inner = sec.substring(i + 1, e)
                    if (inner.startsWith("$")) lit(inner.substring(1).substringBefore('-'))
                    i = e
                }
                ch in "0#?,.%" || ch == 'E' || ch == 'e' && core.isNotEmpty() -> { core.append(ch); seenCore = true }
                else -> lit(ch.toString())
            }
            i++
        }
        val c = core.toString()
        if (c.contains("E+", true) || c.contains("E-", true)) {
            val dec = c.substringAfter('.', "").takeWhile { it == '0' || it == '#' }.length
            return prefix.toString() + String.format(Locale.US, "%." + dec + "E", v) + suffix
        }
        val percent = c.contains('%')
        if (percent) v *= 100
        val dotPos = c.indexOf('.')
        val intPart = if (dotPos >= 0) c.substring(0, dotPos) else c
        val decPart = if (dotPos >= 0) c.substring(dotPos + 1) else ""
        val decimals = decPart.count { it == '0' || it == '#' || it == '?' }
        val minDec = decPart.count { it == '0' }
        val thousands = intPart.contains(',')
        val minInt = intPart.count { it == '0' }
        var s = BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).abs().toPlainString()
        var ip = s.substringBefore('.')
        var dp = s.substringAfter('.', "")
        while (dp.length > minDec && dp.endsWith("0")) dp = dp.dropLast(1)
        while (ip.length < minInt) ip = "0$ip"
        if (minInt == 0 && ip == "0" && (dp.isNotEmpty() || intPart.isNotEmpty())) ip = if (dp.isNotEmpty()) "" else "0"
        if (thousands) ip = ip.reversed().chunked(3).joinToString(",").reversed()
        s = ip + (if (dp.isNotEmpty()) ".$dp" else "")
        if (percent) s += "%"
        return (if (negSign) "-" else "") + prefix + s + suffix
    }

    private fun formatDate(serial: Double, code: String): String {
        val toks = ArrayList<Pair<Char, String>>()   // 'y','m','d','h','s','a' (AM/PM), 'l' literal
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            val lc = ch.lowercaseChar()
            when {
                ch == '"' -> { val e = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }; toks.add('l' to code.substring(i + 1, e)); i = e + 1 }
                ch == '\\' && i + 1 < code.length -> { toks.add('l' to code[i + 1].toString()); i += 2 }
                ch == '[' -> { val e = code.indexOf(']', i).let { if (it < 0) code.length - 1 else it }; i = e + 1 }
                code.regionMatches(i, "AM/PM", 0, 5, true) -> { toks.add('a' to "a"); i += 5 }
                code.regionMatches(i, "A/P", 0, 3, true) -> { toks.add('a' to "a"); i += 3 }
                lc in "ymdhs" -> { val st = i; while (i < code.length && code[i].lowercaseChar() == lc) i++; toks.add(lc to code.substring(st, i)) }
                ch == '_' || ch == '*' -> i += 2
                else -> { toks.add('l' to ch.toString()); i++ }
            }
        }
        val ampm = toks.any { it.first == 'a' }
        val sb = StringBuilder()
        for ((idx, t) in toks.withIndex()) {
            val (k, s) = t
            when (k) {
                'y' -> sb.append(if (s.length >= 3) "yyyy" else "yy")
                'd' -> sb.append(when (s.length) { 1 -> "d"; 2 -> "dd"; 3 -> "EEE"; else -> "EEEE" })
                'h' -> sb.append(if (ampm) (if (s.length >= 2) "hh" else "h") else (if (s.length >= 2) "HH" else "H"))
                's' -> sb.append(if (s.length >= 2) "ss" else "s")
                'a' -> sb.append("a")
                'm' -> {
                    val prevH = toks.take(idx).lastOrNull { it.first != 'l' }?.first == 'h'
                    val nextS = toks.drop(idx + 1).firstOrNull { it.first != 'l' }?.first == 's'
                    if (prevH || nextS) sb.append(if (s.length >= 2) "mm" else "m")
                    else sb.append(when (s.length) { 1 -> "M"; 2 -> "MM"; 3 -> "MMM"; else -> "MMMM" })
                }
                else -> sb.append("'").append(s.replace("'", "''")).append("'")
            }
        }
        val f = SimpleDateFormat(sb.toString(), Locale.getDefault())
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(Math.round((serial - 25569.0) * 86400000.0)))
    }

    /** +1 / -1 decimales sobre un código de formato. */
    fun adjustDecimals(code0: String, d: Int): String {
        val code = if (code0.isEmpty() || code0.equals("General", true)) "0" else code0
        val m = Regex("(#,##0|0)(\\.0+)?").find(code) ?: return code
        val cur = m.groupValues[2].length.let { if (it > 0) it - 1 else 0 }
        val n = maxOf(0, cur + d)
        return code.replaceRange(m.range, m.groupValues[1] + if (n > 0) "." + "0".repeat(n) else "")
    }
}
