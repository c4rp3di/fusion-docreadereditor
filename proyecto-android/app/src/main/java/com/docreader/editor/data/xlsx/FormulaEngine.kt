package com.docreader.editor.data.xlsx

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

class FErr(val code: String) { override fun toString() = code }
class FRange(val rows: Int, val cols: Int, val vals: List<Any?>)
private class FEx(val err: FErr) : RuntimeException(null, null, false, false)
private class Unsupported(val fn: String) : RuntimeException(null, null, false, false)

private sealed class Node
private class NNum(val v: Double) : Node()
private class NStr(val s: String) : Node()
private class NBool(val b: Boolean) : Node()
private class NErr(val code: String) : Node()
private class NRef(val sheet: String?, val r1: Int, val c1: Int, val r2: Int, val c2: Int) : Node()
private class NFunc(val name: String, val args: List<Node>) : Node()
private class NBin(val op: String, val l: Node, val r: Node) : Node()
private class NNeg(val e: Node) : Node()
private class NPct(val e: Node) : Node()

private class Tok(val t: Char, val s: String)

private val refTokRe = Regex("^(?:((?:'[^']+')|[A-Za-z_][\\w.]*)!)?\\$?([A-Za-z]{1,3})\\$?(\\d+)(?::\\$?([A-Za-z]{1,3})\\$?(\\d+))?$")
private val errRe = Regex("#(?:REF!|DIV/0!|N/A|VALUE!|NAME\\?|NUM!|NULL!)")

private fun tokenize(s: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        when {
            ch.isWhitespace() -> i++
            ch == '"' -> {
                val sb = StringBuilder()
                i++
                while (i < s.length) {
                    if (s[i] == '"') {
                        if (i + 1 < s.length && s[i + 1] == '"') { sb.append('"'); i += 2; continue } else break
                    }
                    sb.append(s[i]); i++
                }
                i++
                out.add(Tok('s', sb.toString()))
            }
            ch.isDigit() || (ch == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                    var j = i + 1
                    if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                    if (j < s.length && s[j].isDigit()) { while (j < s.length && s[j].isDigit()) j++; i = j }
                }
                out.add(Tok('n', s.substring(st, i)))
            }
            ch == '#' -> {
                val m = errRe.find(s, i)
                if (m != null && m.range.first == i) { out.add(Tok('e', m.value)); i = m.range.last + 1 }
                else throw IllegalArgumentException("token")
            }
            ch.isLetter() || ch == '_' || ch == '$' || ch == '\'' -> {
                val st = i
                if (ch == '\'') { i++; while (i < s.length && s[i] != '\'') i++; i++ }
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_.$!:")) i++
                out.add(Tok('i', s.substring(st, i)))
            }
            ch == '(' -> { out.add(Tok('(', "(")); i++ }
            ch == ')' -> { out.add(Tok(')', ")")); i++ }
            ch == ',' || ch == ';' -> { out.add(Tok(',', ",")); i++ }
            else -> {
                val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                if (two == "<=" || two == ">=" || two == "<>") { out.add(Tok('o', two)); i += 2 }
                else if (ch in "+-*/^&=<>%") { out.add(Tok('o', ch.toString())); i++ }
                else throw IllegalArgumentException("token")
            }
        }
    }
    return out
}

private class Parser(src: String) {
    private val toks = tokenize(src)
    private var i = 0

    fun parse(): Node {
        val n = cmp()
        if (i < toks.size) throw IllegalArgumentException("extra")
        return n
    }

    private fun peek(): Tok? = toks.getOrNull(i)
    private fun isOp(vararg ops: String) = peek()?.let { it.t == 'o' && it.s in ops } == true

    private fun cmp(): Node {
        var l = concat()
        while (isOp("=", "<>", "<", ">", "<=", ">=")) { val op = toks[i++].s; l = NBin(op, l, concat()) }
        return l
    }

    private fun concat(): Node {
        var l = add()
        while (isOp("&")) { i++; l = NBin("&", l, add()) }
        return l
    }

    private fun add(): Node {
        var l = mul()
        while (isOp("+", "-")) { val op = toks[i++].s; l = NBin(op, l, mul()) }
        return l
    }

    private fun mul(): Node {
        var l = pow()
        while (isOp("*", "/")) { val op = toks[i++].s; l = NBin(op, l, pow()) }
        return l
    }

    private fun pow(): Node {
        var l = unary()
        while (isOp("^")) { i++; l = NBin("^", l, unary()) }
        return l
    }

    private fun unary(): Node {
        if (isOp("-")) { i++; return NNeg(unary()) }
        if (isOp("+")) { i++; return unary() }
        var n = primary()
        while (isOp("%")) { i++; n = NPct(n) }
        return n
    }

    private fun primary(): Node {
        val t = toks.getOrNull(i++) ?: throw IllegalArgumentException("eof")
        return when (t.t) {
            'n' -> NNum(t.s.toDouble())
            's' -> NStr(t.s)
            'e' -> NErr(t.s)
            '(' -> { val e = cmp(); expect(')'); e }
            'i' -> {
                if (peek()?.t == '(') {
                    i++
                    val args = ArrayList<Node>()
                    if (peek()?.t != ')') {
                        while (true) {
                            args.add(cmp())
                            if (peek()?.t == ',') i++ else break
                        }
                    }
                    expect(')')
                    NFunc(t.s.uppercase(), args)
                } else {
                    val up = t.s.uppercase()
                    val m = refTokRe.find(t.s)
                    when {
                        up == "TRUE" -> NBool(true)
                        up == "FALSE" -> NBool(false)
                        m != null -> {
                            val sheet = m.groupValues[1].ifEmpty { null }?.let { if (it.startsWith("'")) it.substring(1, it.length - 1) else it }
                            val c1 = Refs.colToIdx(m.groupValues[2]); val r1 = m.groupValues[3].toInt() - 1
                            val c2 = if (m.groupValues[4].isNotEmpty()) Refs.colToIdx(m.groupValues[4]) else c1
                            val r2 = if (m.groupValues[5].isNotEmpty()) m.groupValues[5].toInt() - 1 else r1
                            NRef(sheet, minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
                        }
                        else -> NErr("#NAME?")
                    }
                }
            }
            else -> throw IllegalArgumentException("unexpected")
        }
    }

    private fun expect(c: Char) { if (toks.getOrNull(i++)?.t != c) throw IllegalArgumentException("expected $c") }
}

class FormulaEngine(private val doc: XlsxDoc) {
    private val parsed = HashMap<String, Node>()
    private val failed = HashSet<String>()
    private val stack = HashSet<Long>()

    fun cellValue(sh: SheetData, r: Int, c: Int): Any? {
        val k = key(r, c)
        if (sh.calc.containsKey(k)) return sh.calc[k]
        val cell = sh.cells[k] ?: return null
        val id = System.identityHashCode(sh).toLong() * 1_000_003L xor k
        if (!stack.add(id)) return FErr("#REF!")
        val v: Any? = try { compute(sh, cell) } finally { stack.remove(id) }
        sh.calc[k] = v
        return v
    }

    private fun cached(cell: Cell): Any? = when (cell.cKind) {
        Cell.NUM -> cell.cText?.toDoubleOrNull()
        Cell.STR -> cell.cText
        Cell.BOOL -> cell.cText == "1"
        Cell.ERR -> FErr(cell.cText ?: "#VALUE!")
        else -> null
    }

    private fun compute(sh: SheetData, cell: Cell): Any? {
        val f = cell.formula
        if (f == null) return when (cell.kind) {
            Cell.NUM -> cell.text.toDoubleOrNull() ?: cell.text
            Cell.STR -> cell.text
            Cell.BOOL -> cell.text == "1" || cell.text.equals("true", true)
            Cell.ERR -> FErr(cell.text)
            else -> null
        }
        if (f in failed) return cached(cell) ?: FErr("#NAME?")
        val node = parsed[f] ?: try { Parser(f).parse().also { parsed[f] = it } } catch (e: Exception) {
            failed.add(f); return cached(cell) ?: FErr("#NAME?")
        }
        return try {
            first(eval(node, sh))
        } catch (e: FEx) {
            e.err
        } catch (e: Unsupported) {
            cached(cell) ?: FErr("#NAME?")
        } catch (e: StackOverflowError) {
            FErr("#REF!")
        }
    }

    private fun first(v: Any?): Any? = if (v is FRange) v.vals.firstOrNull() else v

    // ------------------------------------------------------------ conversiones
    private fun num(v0: Any?): Double {
        val v = first(v0)
        return when (v) {
            is Double -> v
            is Boolean -> if (v) 1.0 else 0.0
            null -> 0.0
            is String -> v.trim().toDoubleOrNull() ?: throw FEx(FErr("#VALUE!"))
            is FErr -> throw FEx(v)
            else -> throw FEx(FErr("#VALUE!"))
        }
    }

    private fun str(v0: Any?): String {
        val v = first(v0)
        return when (v) {
            is Double -> NumFmt.numToStr(v)
            is Boolean -> if (v) "TRUE" else "FALSE"
            null -> ""
            is String -> v
            is FErr -> throw FEx(v)
            else -> v.toString()
        }
    }

    private fun bool(v0: Any?): Boolean {
        val v = first(v0)
        return when (v) {
            is Boolean -> v
            is Double -> v != 0.0
            is String -> when (v.uppercase()) { "TRUE" -> true; "FALSE" -> false; else -> throw FEx(FErr("#VALUE!")) }
            null -> false
            is FErr -> throw FEx(v)
            else -> false
        }
    }

    private fun rank(v: Any?) = when (v) { is Double -> 0; is String -> 1; else -> 2 }

    private fun compare(a0: Any?, b0: Any?): Int {
        val a = first(a0); val b = first(b0)
        if (a is FErr) throw FEx(a)
        if (b is FErr) throw FEx(b)
        val x: Any = a ?: (if (b is String) "" else if (b is Boolean) false else 0.0)
        val y: Any = b ?: (if (a is String) "" else if (a is Boolean) false else 0.0)
        val rx = rank(x); val ry = rank(y)
        if (rx != ry) return rx.compareTo(ry)
        return when (x) {
            is Double -> x.compareTo(y as Double)
            is String -> x.compareTo(y as String, ignoreCase = true)
            else -> (x as Boolean).compareTo(y as Boolean)
        }
    }

    // ------------------------------------------------------------ evaluación
    private fun eval(n: Node, sh: SheetData): Any? = when (n) {
        is NNum -> n.v
        is NStr -> n.s
        is NBool -> n.b
        is NErr -> FErr(n.code)
        is NRef -> ref(n, sh)
        is NNeg -> -num(eval(n.e, sh))
        is NPct -> num(eval(n.e, sh)) / 100.0
        is NBin -> binary(n, sh)
        is NFunc -> call(n, sh)
    }

    private fun ref(n: NRef, sh: SheetData): Any? {
        val t = if (n.sheet == null) sh else doc.sheets.firstOrNull { it.name.equals(n.sheet, true) } ?: throw FEx(FErr("#REF!"))
        if (n.r1 == n.r2 && n.c1 == n.c2) return cellValue(t, n.r1, n.c1)
        val r2 = if (n.r2 - n.r1 > 5000) minOf(n.r2, maxOf(n.r1, t.maxRow())) else n.r2
        val rows = r2 - n.r1 + 1
        val cols = n.c2 - n.c1 + 1
        val vals = ArrayList<Any?>(rows * cols)
        for (r in n.r1..r2) for (c in n.c1..n.c2) vals.add(cellValue(t, r, c))
        return FRange(rows, cols, vals)
    }

    private fun binary(n: NBin, sh: SheetData): Any? {
        val a = eval(n.l, sh)
        val b = eval(n.r, sh)
        return when (n.op) {
            "+" -> num(a) + num(b)
            "-" -> num(a) - num(b)
            "*" -> num(a) * num(b)
            "/" -> { val d = num(b); if (d == 0.0) throw FEx(FErr("#DIV/0!")); num(a) / d }
            "^" -> { val r = num(a).pow(num(b)); if (r.isNaN() || r.isInfinite()) throw FEx(FErr("#NUM!")); r }
            "&" -> str(a) + str(b)
            "=" -> compare(a, b) == 0
            "<>" -> compare(a, b) != 0
            "<" -> compare(a, b) < 0
            ">" -> compare(a, b) > 0
            "<=" -> compare(a, b) <= 0
            else -> compare(a, b) >= 0
        }
    }

    /** Números de los argumentos: en rangos solo los numéricos; directos se convierten. */
    private fun nums(vals: List<Any?>): List<Double> {
        val out = ArrayList<Double>()
        for (v in vals) {
            if (v is FRange) {
                for (x in v.vals) { if (x is Double) out.add(x) else if (x is FErr) throw FEx(x) }
            } else out.add(num(v))
        }
        return out
    }

    private fun flat(vals: List<Any?>): List<Any?> = vals.flatMap { if (it is FRange) it.vals else listOf(it) }

    private fun matcher(crit0: Any?): (Any?) -> Boolean {
        val crit = first(crit0)
        if (crit is String) {
            val m = Regex("^(>=|<=|<>|>|<|=)(.*)$").find(crit)
            val op = m?.groupValues?.get(1) ?: "="
            val operand = m?.groupValues?.get(2) ?: crit
            val d = operand.toDoubleOrNull()
            return { v ->
                val x = first(v)
                if (d != null && x is Double) when (op) {
                    "=" -> x == d; "<>" -> x != d; "<" -> x < d; ">" -> x > d; "<=" -> x <= d; else -> x >= d
                } else {
                    val s = if (x is Double) NumFmt.numToStr(x) else (x?.toString() ?: "")
                    val pat = StringBuilder("^")
                    for (ch in operand) when (ch) {
                        '*' -> pat.append(".*")
                        '?' -> pat.append(".")
                        else -> pat.append(Regex.escape(ch.toString()))
                    }
                    pat.append("$")
                    val re = Regex(pat.toString(), RegexOption.IGNORE_CASE)
                    val wild = operand.contains('*') || operand.contains('?')
                    val eq = if (wild) re.matches(s) else s.equals(operand, true)
                    when (op) { "=" -> eq; "<>" -> !eq; else -> false }
                }
            }
        }
        val d = if (crit is Double) crit else null
        return { v -> val x = first(v); if (d != null) x is Double && x == d else x == crit }
    }

    private fun serialToDate(s: Double): LocalDate = LocalDate.ofEpochDay((floor(s) - 25569).toLong())
    private fun dateToSerial(d: LocalDate): Double = (d.toEpochDay() + 25569).toDouble()

    private fun call(n: NFunc, sh: SheetData): Any? {
        val a = n.args
        when (n.name) {
            "IF" -> {
                if (a.size < 2) throw FEx(FErr("#VALUE!"))
                return if (bool(eval(a[0], sh))) eval(a[1], sh) else if (a.size > 2) eval(a[2], sh) else false
            }
            "IFERROR" -> return try {
                val v = first(eval(a[0], sh)); if (v is FErr) eval(a[1], sh) else v
            } catch (e: FEx) { eval(a[1], sh) }
            "AND" -> return flat(a.map { eval(it, sh) }).all { bool(it) }
            "OR" -> return flat(a.map { eval(it, sh) }).any { bool(it) }
            "TODAY" -> return dateToSerial(LocalDate.now())
            "NOW" -> {
                val ms = System.currentTimeMillis() + java.util.TimeZone.getDefault().rawOffset
                return ms / 86400000.0 + 25569.0
            }
            "PI" -> return Math.PI
        }
        val v = a.map { eval(it, sh) }
        fun arg(i: Int): Any? = v.getOrNull(i)
        fun need(k: Int) { if (v.size < k) throw FEx(FErr("#VALUE!")) }
        return when (n.name) {
            "SUM" -> nums(v).sum()
            "AVERAGE" -> { val l = nums(v); if (l.isEmpty()) throw FEx(FErr("#DIV/0!")); l.sum() / l.size }
            "MIN" -> nums(v).minOrNull() ?: 0.0
            "MAX" -> nums(v).maxOrNull() ?: 0.0
            "PRODUCT" -> nums(v).fold(1.0) { x, y -> x * y }
            "MEDIAN" -> {
                val l = nums(v).sorted(); if (l.isEmpty()) throw FEx(FErr("#NUM!"))
                if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2
            }
            "COUNT" -> v.sumOf { x -> if (x is FRange) x.vals.count { it is Double } else if (first(x) is Double) 1 else 0 }.toDouble()
            "COUNTA" -> flat(v).count { it != null }.toDouble()
            "COUNTBLANK" -> flat(v).count { it == null || it == "" }.toDouble()
            "ABS" -> { need(1); abs(num(arg(0))) }
            "INT" -> { need(1); floor(num(arg(0))) }
            "SQRT" -> { need(1); val x = num(arg(0)); if (x < 0) throw FEx(FErr("#NUM!")); sqrt(x) }
            "EXP" -> { need(1); Math.exp(num(arg(0))) }
            "LN" -> { need(1); val x = num(arg(0)); if (x <= 0) throw FEx(FErr("#NUM!")); Math.log(x) }
            "LOG10" -> { need(1); val x = num(arg(0)); if (x <= 0) throw FEx(FErr("#NUM!")); Math.log10(x) }
            "LOG" -> { need(1); val x = num(arg(0)); val b = if (v.size > 1) num(arg(1)) else 10.0
                if (x <= 0 || b <= 0 || b == 1.0) throw FEx(FErr("#NUM!")); Math.log(x) / Math.log(b) }
            "POWER" -> { need(2); num(arg(0)).pow(num(arg(1))) }
            "MOD" -> { need(2); val d = num(arg(1)); if (d == 0.0) throw FEx(FErr("#DIV/0!")); val x = num(arg(0)); x - d * floor(x / d) }
            "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                need(1)
                val x = num(arg(0)); val d = if (v.size > 1) num(arg(1)).toInt() else 0
                val mode = when (n.name) { "ROUND" -> java.math.RoundingMode.HALF_UP; "ROUNDUP" -> java.math.RoundingMode.UP; else -> java.math.RoundingMode.DOWN }
                java.math.BigDecimal(x.toString()).setScale(d, mode).toDouble()
            }
            "LEN" -> { need(1); str(arg(0)).length.toDouble() }
            "UPPER" -> { need(1); str(arg(0)).uppercase() }
            "LOWER" -> { need(1); str(arg(0)).lowercase() }
            "TRIM" -> { need(1); str(arg(0)).trim().replace(Regex(" +"), " ") }
            "LEFT" -> { need(1); str(arg(0)).take(if (v.size > 1) num(arg(1)).toInt().coerceAtLeast(0) else 1) }
            "RIGHT" -> { need(1); str(arg(0)).takeLast(if (v.size > 1) num(arg(1)).toInt().coerceAtLeast(0) else 1) }
            "MID" -> { need(3); val s = str(arg(0)); val st = (num(arg(1)).toInt() - 1).coerceAtLeast(0); val l = num(arg(2)).toInt().coerceAtLeast(0)
                if (st >= s.length) "" else s.substring(st, minOf(s.length, st + l)) }
            "CONCAT", "CONCATENATE" -> flat(v).joinToString("") { str(it) }
            "REPT" -> { need(2); str(arg(0)).repeat(num(arg(1)).toInt().coerceIn(0, 10000)) }
            "SUBSTITUTE" -> { need(3); str(arg(0)).replace(str(arg(1)), str(arg(2))) }
            "FIND", "SEARCH" -> {
                need(2)
                val st = if (v.size > 2) num(arg(2)).toInt() - 1 else 0
                val i = str(arg(1)).indexOf(str(arg(0)), st.coerceAtLeast(0), n.name == "SEARCH")
                if (i < 0) throw FEx(FErr("#VALUE!")) else (i + 1).toDouble()
            }
            "VALUE" -> { need(1); str(arg(0)).trim().toDoubleOrNull() ?: throw FEx(FErr("#VALUE!")) }
            "TEXT" -> { need(2); val x = first(arg(0)); if (x is Double) NumFmt.format(x, str(arg(1))) else str(x) }
            "NOT" -> { need(1); !bool(arg(0)) }
            "ISBLANK" -> first(arg(0)) == null
            "ISNUMBER" -> first(arg(0)) is Double
            "ISTEXT" -> first(arg(0)) is String
            "ISERROR" -> first(arg(0)) is FErr
            "YEAR" -> { need(1); serialToDate(num(arg(0))).year.toDouble() }
            "MONTH" -> { need(1); serialToDate(num(arg(0))).monthValue.toDouble() }
            "DAY" -> { need(1); serialToDate(num(arg(0))).dayOfMonth.toDouble() }
            "DATE" -> { need(3); dateToSerial(LocalDate.of(num(arg(0)).toInt(), 1, 1).plusMonths((num(arg(1)).toLong() - 1)).plusDays(num(arg(2)).toLong() - 1)) }
            "SUMIF", "COUNTIF", "AVERAGEIF" -> {
                need(2)
                val rng = arg(0) as? FRange ?: FRange(1, 1, listOf(arg(0)))
                val m = matcher(arg(1))
                val sumR = (if (v.size > 2) arg(2) as? FRange else null) ?: rng
                var cnt = 0; var sum = 0.0
                for (i in rng.vals.indices) {
                    if (m(rng.vals[i])) {
                        cnt++
                        val x = sumR.vals.getOrNull(i)
                        if (x is Double) sum += x
                    }
                }
                when (n.name) {
                    "COUNTIF" -> cnt.toDouble()
                    "SUMIF" -> sum
                    else -> if (cnt == 0) throw FEx(FErr("#DIV/0!")) else sum / cnt
                }
            }
            "VLOOKUP" -> {
                need(3)
                val key = first(arg(0))
                val t = arg(1) as? FRange ?: throw FEx(FErr("#N/A"))
                val col = num(arg(2)).toInt()
                if (col < 1 || col > t.cols) throw FEx(FErr("#REF!"))
                val exact = v.size > 3 && !bool(arg(3))
                var found = -1
                for (r in 0 until t.rows) {
                    val cur = t.vals[r * t.cols]
                    if (exact) { if (cur != null && compare(cur, key) == 0) { found = r; break } }
                    else if (cur != null && rank(cur) == rank(key) && compare(cur, key) <= 0) found = r
                }
                if (found < 0) throw FEx(FErr("#N/A"))
                t.vals[found * t.cols + col - 1]
            }
            "MATCH" -> {
                need(2)
                val key = first(arg(0))
                val t = arg(1) as? FRange ?: throw FEx(FErr("#N/A"))
                val type = if (v.size > 2) num(arg(2)).toInt() else 1
                var found = -1
                for (i in t.vals.indices) {
                    val cur = t.vals[i] ?: continue
                    if (type == 0) { if (compare(cur, key) == 0) { found = i; break } }
                    else if (rank(cur) == rank(key) && compare(cur, key) <= 0) found = i
                }
                if (found < 0) throw FEx(FErr("#N/A")) else (found + 1).toDouble()
            }
            "INDEX" -> {
                need(2)
                val t = arg(0) as? FRange ?: return arg(0)
                val r = num(arg(1)).toInt()
                val c = if (v.size > 2) num(arg(2)).toInt() else 1
                val rr = if (t.rows == 1 && v.size == 2) 1 else r
                val cc = if (t.rows == 1 && v.size == 2) r else c
                if (rr < 1 || rr > t.rows || cc < 1 || cc > t.cols) throw FEx(FErr("#REF!"))
                t.vals[(rr - 1) * t.cols + cc - 1]
            }
            "SUMIFS", "COUNTIFS", "AVERAGEIFS" -> {
                val isCount = n.name == "COUNTIFS"
                val valR = if (isCount) null else (arg(0) as? FRange ?: FRange(1, 1, listOf(arg(0))))
                val pairs = ArrayList<Pair<FRange, (Any?) -> Boolean>>()
                var i = if (isCount) 0 else 1
                while (i + 1 < v.size) {
                    pairs.add((arg(i) as? FRange ?: FRange(1, 1, listOf(arg(i)))) to matcher(arg(i + 1)))
                    i += 2
                }
                if (pairs.isEmpty()) throw FEx(FErr("#VALUE!"))
                var cnt = 0
                var sum = 0.0
                for (k in 0 until pairs[0].first.vals.size) {
                    if (pairs.all { (r, m) -> m(r.vals.getOrNull(k)) }) {
                        cnt++
                        val x = valR?.vals?.getOrNull(k)
                        if (x is Double) sum += x
                    }
                }
                when (n.name) {
                    "COUNTIFS" -> cnt.toDouble()
                    "SUMIFS" -> sum
                    else -> if (cnt == 0) throw FEx(FErr("#DIV/0!")) else sum / cnt
                }
            }
            "IFS" -> {
                var i = 0
                var res: Any? = null
                var found = false
                while (i + 1 < v.size) { if (bool(arg(i))) { res = arg(i + 1); found = true; break }; i += 2 }
                if (!found) throw FEx(FErr("#N/A"))
                res
            }
            "SWITCH" -> {
                need(2)
                val key = first(arg(0))
                var i = 1
                var res: Any? = null
                var found = false
                while (i + 1 < v.size) { if (compare(arg(i), key) == 0) { res = arg(i + 1); found = true; break }; i += 2 }
                if (!found) { if (v.size % 2 == 0) res = arg(v.size - 1) else throw FEx(FErr("#N/A")) }
                res
            }
            "XLOOKUP" -> {
                need(3)
                val key = first(arg(0))
                val look = arg(1) as? FRange ?: throw FEx(FErr("#N/A"))
                val ret = arg(2) as? FRange ?: throw FEx(FErr("#N/A"))
                var idx = -1
                for (i in look.vals.indices) { val cur = look.vals[i]; if (cur != null && compare(cur, key) == 0) { idx = i; break } }
                if (idx < 0) { if (v.size > 3) arg(3) else throw FEx(FErr("#N/A")) } else ret.vals.getOrNull(idx)
            }
            "TEXTJOIN" -> {
                need(3)
                val d = str(arg(0))
                val ignore = bool(arg(1))
                flat(v.drop(2)).filter { !(ignore && (it == null || it == "")) }.joinToString(d) { str(it) }
            }
            "LARGE", "SMALL" -> {
                need(2)
                val l = nums(listOf(arg(0))).sorted()
                val k = num(arg(1)).toInt()
                if (k < 1 || k > l.size) throw FEx(FErr("#NUM!"))
                if (n.name == "LARGE") l[l.size - k] else l[k - 1]
            }
            "SIGN" -> { need(1); Math.signum(num(arg(0))) }
            "TRUNC" -> {
                need(1)
                val d = if (v.size > 1) num(arg(1)).toInt() else 0
                java.math.BigDecimal(num(arg(0)).toString()).setScale(d, java.math.RoundingMode.DOWN).toDouble()
            }
            else -> throw Unsupported(n.name)
        }
    }
}
