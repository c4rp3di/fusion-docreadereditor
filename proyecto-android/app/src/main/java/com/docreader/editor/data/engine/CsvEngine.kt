package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri
import com.docreader.editor.data.xlsx.Cell
import com.docreader.editor.data.xlsx.SheetData
import com.docreader.editor.data.xlsx.StyleBook
import com.docreader.editor.data.xlsx.XlsxDoc
import com.docreader.editor.data.xlsx.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/** CSV/TSV como tabla editable (reutiliza el editor de hojas). Detecta el separador y conserva BOM y delimitador. */
class CsvEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("csv", "tsv")
    override val supportsEditing = true
    override val requiresOcr = false

    private val numRe = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?$")

    override suspend fun open(context: Context, uri: Uri, extension: String) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("No se pudo abrir el archivo")
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val off = if (bom) 3 else 0
        val text = String(bytes, off, bytes.size - off, Charsets.UTF_8)
        val delim = if (extension == "tsv") '\t' else detect(text)
        val rows = parse(text, delim)
        val sh = SheetData("Datos", "xl/worksheets/sheet1.xml")
        var maxC = 0
        for ((r, row) in rows.withIndex()) {
            maxC = max(maxC, row.size)
            for ((c, f) in row.withIndex()) {
                if (f.isEmpty()) continue
                sh.cells[key(r, c)] = if (numRe.matches(f)) Cell(Cell.NUM, f, null, 0) else Cell(Cell.STR, f, null, 0)
            }
        }
        sh.rows = min(5000, max(rows.size + 15, 30))
        sh.colsCap = min(100, max(maxC + 3, 10))
        val doc = XlsxDoc(ByteArray(0), mutableListOf(sh), StyleBook(null, false))
        doc.isCsv = true
        doc.csvDelimiter = delim
        doc.csvBom = bom
        DocumentHandle(uri, extension, text, doc)
    }

    private fun detect(text: String): Char {
        val first = text.substringBefore('\n')
        val counts = listOf(',', ';', '\t').associateWith { d -> first.count { it == d } }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun parse(text: String, d: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                q -> if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else q = false
                } else sb.append(ch)
                ch == '"' -> q = true
                ch == d -> { row.add(sb.toString()); sb.clear() }
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(sb.toString()); sb.clear()
                    rows.add(row); row = ArrayList()
                }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }

    private fun quote(v: String, d: Char): String =
        if (v.any { it == d || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    override suspend fun extractText(context: Context, handle: DocumentHandle) = handle.text

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean {
        val doc = handle.model as? XlsxDoc ?: return false
        val sh = doc.sheets.firstOrNull() ?: return false
        return try {
            val d = doc.csvDelimiter
            val maxR = sh.maxRow()
            val maxC = sh.maxCol()
            val sb = StringBuilder()
            if (doc.csvBom) sb.append('\uFEFF')
            for (r in 0..maxR) {
                for (c in 0..maxC) {
                    val cell = sh.cell(r, c)
                    val v = when {
                        cell == null -> ""
                        cell.formula != null -> doc.display(sh, r, c)
                        else -> doc.raw(sh, r, c)
                    }
                    sb.append(quote(v, d))
                    if (c < maxC) sb.append(d)
                }
                if (r < maxR) sb.append('\n')
            }
            val bytes = sb.toString().toByteArray(Charsets.UTF_8)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(outputUri, "wt")?.use { it.write(bytes) } != null
            }
        } catch (e: Exception) {
            false
        }
    }
}
