package com.docreader.editor.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Texto con posiciones por página: nativo (PDFBox) y, bajo demanda, OCR (ML Kit) para páginas escaneadas. */
class PdfTextExtractor(private val file: File) {
    private var doc: PDDocument? = null
    private val mutex = Mutex()
    private val native = HashMap<Int, List<PWord>>()
    private val ocr = HashMap<Int, List<PWord>>()

    private class Collector : PDFTextStripper() {
        val words = ArrayList<PWord>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotBlank()) words.add(PWord(sb.toString(), l, t, r, b))
                sb.clear()
                l = Float.MAX_VALUE; t = Float.MAX_VALUE; r = -Float.MAX_VALUE; b = -Float.MAX_VALUE
            }
            for (tp in textPositions) {
                val u = tp.unicode ?: continue
                if (u.isBlank()) { flush(); continue }
                val x = tp.xDirAdj
                val y = tp.yDirAdj
                l = min(l, x); r = max(r, x + tp.widthDirAdj)
                t = min(t, y - tp.heightDir); b = max(b, y)
                sb.append(u)
            }
            flush()
        }

        override fun writeLineSeparator() { words.lastOrNull()?.endsLine = true }
        override fun writeWordSeparator() {}
    }

    suspend fun nativeWords(page: Int): List<PWord> = mutex.withLock {
        native[page] ?: withContext(Dispatchers.IO) {
            try {
                val d = doc ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also { doc = it }
                val c = Collector()
                c.sortByPosition = true
                c.startPage = page + 1
                c.endPage = page + 1
                c.getText(d)
                c.words.lastOrNull()?.endsLine = true
                c.words
            } catch (e: Exception) {
                emptyList()
            }
        }.also { native[page] = it }
    }

    /** Palabras de la página: nativas si las hay; si no, las de OCR ya reconocidas. */
    suspend fun allWords(page: Int): List<PWord> {
        val n = nativeWords(page)
        return if (n.isNotEmpty()) n else ocr[page] ?: emptyList()
    }

    fun hasOcr(page: Int) = ocr.containsKey(page)

    suspend fun ocrPage(page: Int): List<PWord> = withContext(Dispatchers.IO) {
        ocr[page]?.let { return@withContext it }
        val out = ArrayList<PWord>()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(page).use { pg ->
                        val scale = 200f / 72f
                        val w = min((pg.width * scale).toInt(), 2600)
                        val sc = w.toFloat() / pg.width
                        val h = (pg.height * sc).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                        for (blk in res.textBlocks) for (line in blk.lines) {
                            for ((i, el) in line.elements.withIndex()) {
                                val bb = el.boundingBox ?: continue
                                out.add(PWord(el.text, bb.left / sc, bb.top / sc, bb.right / sc, bb.bottom / sc, i == line.elements.size - 1))
                            }
                        }
                        bmp.recycle()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            recognizer.close()
        }
        ocr[page] = out
        out
    }

    suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { doc?.close() }; doc = null }
    }

    companion object {
        fun textOf(words: List<PWord>): String {
            val sb = StringBuilder()
            for (w in words) { sb.append(w.text); sb.append(if (w.endsLine) '\n' else ' ') }
            return sb.toString().trim()
        }

        /** Agrupa líneas en párrafos por la separación vertical (para exportar a Word). */
        fun paragraphs(words: List<PWord>): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var lastBottom = 0f
            var lineH = 0f
            var line = StringBuilder()
            var lt = 0f; var lb = 0f
            fun endLine() {
                if (line.isEmpty()) return
                val h = max(1f, lb - lt)
                if (cur.isNotEmpty() && lt - lastBottom > 0.8f * max(h, lineH)) { out.add(cur.toString().trim()); cur.clear() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(line.toString().trim())
                lastBottom = lb; lineH = h
                line = StringBuilder()
            }
            for (w in words) {
                if (line.isEmpty()) { lt = w.t; lb = w.b } else { lt = min(lt, w.t); lb = max(lb, w.b) }
                line.append(w.text).append(' ')
                if (w.endsLine) endLine()
            }
            endLine()
            if (cur.isNotEmpty()) out.add(cur.toString().trim())
            return out
        }
    }
}
