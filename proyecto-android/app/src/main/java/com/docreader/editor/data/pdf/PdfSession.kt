package com.docreader.editor.data.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Copia de trabajo de un PDF. Las anotaciones se acumulan en memoria (con deshacer) y se escriben
 * como contenido de página al guardar; las operaciones de páginas se aplican al instante con historial.
 */
class PdfSession private constructor(private val dir: File, val work: File) {
    val annots = ArrayList<PdfAnnot>()
    val undo = ArrayDeque<List<PdfAnnot>>()
    val redo = ArrayDeque<List<PdfAnnot>>()
    private val history = ArrayDeque<File>()
    private val lock = Mutex()
    var version = 0
        private set
    /** true si el archivo de trabajo tiene cambios (páginas, formularios, anotaciones escritas) sin guardar. */
    var fileOps = false

    companion object {
        suspend fun create(ctx: Context, uri: Uri, password: String?): PdfSession = withContext(Dispatchers.IO) {
            val root = File(ctx.cacheDir, "pdfwork")
            root.listFiles()?.forEach { it.deleteRecursively() }
            val dir = File(root, System.nanoTime().toString())
            dir.mkdirs()
            val orig = File(dir, "orig.pdf")
            ctx.contentResolver.openInputStream(uri)?.use { i -> orig.outputStream().use { o -> i.copyTo(o) } }
                ?: throw java.io.IOException("No se pudo abrir el archivo")
            val work = File(dir, "work.pdf")
            val doc = try {
                PDDocument.load(orig, password ?: "", MemoryUsageSetting.setupTempFileOnly())
            } catch (e: InvalidPasswordException) {
                throw PdfPasswordException()
            }
            try {
                if (doc.isEncrypted) {
                    doc.setAllSecurityToBeRemoved(true)
                    doc.save(work)
                } else orig.copyTo(work, true)
            } finally {
                doc.close()
            }
            PdfSession(dir, work)
        }
    }

    fun destroy() { runCatching { dir.deleteRecursively() } }

    // ------------------------------------------------------------ anotaciones pendientes
    fun checkpointAnnots() {
        undo.addLast(ArrayList(annots))
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
    }

    fun undoAnnot(): Boolean {
        if (undo.isEmpty()) return false
        redo.addLast(ArrayList(annots))
        annots.clear(); annots.addAll(undo.removeLast())
        return true
    }

    fun redoAnnot(): Boolean {
        if (redo.isEmpty()) return false
        undo.addLast(ArrayList(annots))
        annots.clear(); annots.addAll(redo.removeLast())
        return true
    }

    // ------------------------------------------------------------ edición del archivo
    private fun snapshotHistory() {
        val f = File(dir, "h${System.nanoTime()}.pdf")
        work.copyTo(f, true)
        history.addLast(f)
        if (history.size > 6) history.removeFirst().delete()
    }

    private suspend fun <T> edit(block: (PDDocument, MutableList<PDDocument>) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            snapshotHistory()
            val doc = PDDocument.load(work, MemoryUsageSetting.setupTempFileOnly())
            val extra = ArrayList<PDDocument>()
            try {
                val r = block(doc, extra)
                val tmp = File(dir, "tmp.pdf")
                doc.save(tmp)
                tmp.copyTo(work, true)
                tmp.delete()
                version++
                fileOps = true
                r
            } catch (e: Exception) {
                history.removeLastOrNull()?.delete()
                throw e
            } finally {
                extra.forEach { runCatching { it.close() } }
                doc.close()
            }
        }
    }

    suspend fun undoFileOp(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val f = history.removeLastOrNull() ?: return@withContext false
            f.copyTo(work, true)
            f.delete()
            version++
            fileOps = true
            true
        }
    }

    suspend fun commit() {
        if (annots.isEmpty()) return
        val list = ArrayList(annots)
        edit { doc, _ -> apply(doc, list) }
        annots.clear(); undo.clear(); redo.clear()
    }

    suspend fun rotate(page: Int, deg: Int) = edit { d, _ -> val p = d.getPage(page); p.rotation = ((p.rotation + deg) % 360 + 360) % 360 }

    suspend fun delete(page: Int) = edit { d, _ ->
        if (d.numberOfPages <= 1) throw IllegalStateException("No se puede eliminar la única página")
        d.removePage(page)
    }

    suspend fun move(from: Int, to: Int) = edit { d, _ ->
        val p = d.getPage(from)
        d.pages.remove(from)
        if (to >= d.numberOfPages) d.pages.add(p) else d.pages.insertBefore(p, d.getPage(to))
    }

    suspend fun insertBlank(after: Int) = edit { d, _ ->
        val ref = d.getPage(after).mediaBox
        val np = PDPage(PDRectangle(ref.width, ref.height))
        if (after + 1 >= d.numberOfPages) d.pages.add(np) else d.pages.insertBefore(np, d.getPage(after + 1))
    }

    suspend fun append(other: File) = edit { d, extra ->
        val o = PDDocument.load(other, MemoryUsageSetting.setupTempFileOnly())
        extra.add(o)
        PDFMergerUtility().appendDocument(d, o)
    }

    suspend fun extract(pages: List<Int>, out: OutputStream) = lock.withLock {
        withContext(Dispatchers.IO) {
            val src = PDDocument.load(work, MemoryUsageSetting.setupTempFileOnly())
            val nd = PDDocument()
            try {
                for (i in pages) nd.importPage(src.getPage(i))
                nd.save(out)
            } finally {
                nd.close(); src.close()
            }
        }
    }

    /** Marcadores del PDF: (título, página 0-based o -1, nivel). */
    suspend fun outline(): List<Triple<String, Int, Int>> = lock.withLock {
        withContext(Dispatchers.IO) {
            val doc = PDDocument.load(work, MemoryUsageSetting.setupTempFileOnly())
            try {
                val out = ArrayList<Triple<String, Int, Int>>()
                val root = doc.documentCatalog.documentOutline ?: return@withContext out
                fun walk(node: PDOutlineNode, depth: Int) {
                    var cur: PDOutlineItem? = node.firstChild
                    while (cur != null && out.size < 500) {
                        var idx = -1
                        try {
                            val pg = cur.findDestinationPage(doc)
                            if (pg != null) idx = doc.pages.indexOf(pg)
                        } catch (_: Exception) { }
                        out.add(Triple(cur.title ?: "", idx, depth))
                        walk(cur, depth + 1)
                        cur = cur.nextSibling
                    }
                }
                walk(root, 0)
                out
            } finally {
                doc.close()
            }
        }
    }

    // ------------------------------------------------------------ formularios
    suspend fun formFields(): List<FormField> = lock.withLock {
        withContext(Dispatchers.IO) {
            val doc = PDDocument.load(work, MemoryUsageSetting.setupTempFileOnly())
            try {
                val form = doc.documentCatalog.acroForm ?: return@withContext emptyList()
                val out = ArrayList<FormField>()
                for (f in form.fieldTree) {
                    if (f is PDNonTerminalField || f.isReadOnly) continue
                    val name = f.fullyQualifiedName ?: continue
                    when (f) {
                        is PDTextField -> out.add(FormField(name, FormField.TEXT, f.value ?: "", emptyList()))
                        is PDCheckBox -> out.add(FormField(name, FormField.CHECK, if (f.isChecked) "true" else "false", emptyList()))
                        is PDRadioButton -> out.add(FormField(name, FormField.RADIO, f.value ?: "", f.onValues.toList()))
                        is PDChoice -> out.add(FormField(name, FormField.CHOICE, f.value.firstOrNull() ?: "", f.options.toList()))
                    }
                }
                out
            } finally {
                doc.close()
            }
        }
    }

    suspend fun fillForm(values: Map<String, String>) = edit { doc, _ ->
        val form = doc.documentCatalog.acroForm ?: return@edit
        if (form.defaultResources == null) {
            val res = PDResources()
            res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            form.defaultResources = res
        }
        form.setNeedAppearances(true)
        for ((name, v) in values) {
            val f = form.getField(name) ?: continue
            try {
                when (f) {
                    is PDTextField -> f.value = v
                    is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                    is PDRadioButton -> f.value = v
                    is PDChoice -> f.setValue(v)
                }
            } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------ escribir anotaciones
    private fun loadFont(doc: PDDocument): PDFont {
        for (path in listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf", "/system/fonts/DroidSans.ttf")) {
            val f = File(path)
            if (f.exists()) try { return PDType0Font.load(doc, f) } catch (_: Exception) { }
        }
        return PDType1Font.HELVETICA
    }

    private fun safe(font: PDFont, s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            try { font.encode(ch.toString()); sb.append(ch) } catch (_: Exception) { sb.append('?') }
        }
        return sb.toString()
    }

    private fun rectPdf(g: PageGeom, r: android.graphics.RectF): FloatArray {
        val x1 = g.x(r.left, r.top); val y1 = g.y(r.left, r.top)
        val x2 = g.x(r.right, r.bottom); val y2 = g.y(r.right, r.bottom)
        return floatArrayOf(min(x1, x2), min(y1, y2), abs(x2 - x1), abs(y2 - y1))
    }

    private fun apply(doc: PDDocument, list: List<PdfAnnot>) {
        var font: PDFont? = null
        for ((pageIdx, items) in list.groupBy { it.page }) {
            if (pageIdx !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(pageIdx)
            val g = PageGeom(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for (a in items) when (a) {
                    is Markup -> {
                        cs.saveGraphicsState()
                        if (a.kind == Markup.HIGHLIGHT) {
                            val gs = PDExtendedGraphicsState()
                            gs.blendMode = BlendMode.MULTIPLY
                            cs.setGraphicsStateParameters(gs)
                            cs.setNonStrokingColor(Color.red(a.color), Color.green(a.color), Color.blue(a.color))
                            for (r in a.rects) { val q = rectPdf(g, r); cs.addRect(q[0], q[1], q[2], q[3]); cs.fill() }
                        } else {
                            cs.setStrokingColor(Color.red(a.color), Color.green(a.color), Color.blue(a.color))
                            cs.setLineWidth(1f)
                            for (r in a.rects) {
                                val yy = if (a.kind == Markup.UNDERLINE) r.bottom else (r.top + r.bottom) / 2
                                cs.moveTo(g.x(r.left, yy), g.y(r.left, yy))
                                cs.lineTo(g.x(r.right, yy), g.y(r.right, yy))
                                cs.stroke()
                            }
                        }
                        cs.restoreGraphicsState()
                    }
                    is Ink -> if (a.pts.size >= 2) {
                        cs.saveGraphicsState()
                        cs.setStrokingColor(Color.red(a.color), Color.green(a.color), Color.blue(a.color))
                        cs.setLineWidth(a.width)
                        cs.setLineCapStyle(1)
                        cs.setLineJoinStyle(1)
                        cs.moveTo(g.x(a.pts[0].x, a.pts[0].y), g.y(a.pts[0].x, a.pts[0].y))
                        for (i in 1 until a.pts.size) cs.lineTo(g.x(a.pts[i].x, a.pts[i].y), g.y(a.pts[i].x, a.pts[i].y))
                        cs.stroke()
                        cs.restoreGraphicsState()
                    }
                    is TextBox -> {
                        val f = font ?: loadFont(doc).also { font = it }
                        drawText(cs, g, f, a.x, a.y, a.text, a.size, a.color)
                    }
                    is CoverText -> {
                        cs.saveGraphicsState()
                        cs.setNonStrokingColor(255, 255, 255)
                        for (r in a.rects) { val q = rectPdf(g, r); cs.addRect(q[0] - 0.5f, q[1] - 0.5f, q[2] + 1f, q[3] + 1f); cs.fill() }
                        cs.restoreGraphicsState()
                        val f = font ?: loadFont(doc).also { font = it }
                        val first = a.rects.firstOrNull()
                        if (first != null && a.text.isNotEmpty()) drawText(cs, g, f, first.left, first.top, a.text, a.size, a.color)
                    }
                    is ImageStamp -> {
                        val img = PDImageXObject.createFromByteArray(doc, a.png, "img")
                        cs.saveGraphicsState()
                        cs.transform(Matrix.getRotateInstance(Math.toRadians(g.rot.toDouble()), g.x(a.x, a.y + a.h), g.y(a.x, a.y + a.h)))
                        cs.drawImage(img, 0f, 0f, a.w, a.h)
                        cs.restoreGraphicsState()
                    }
                }
            }
        }
    }

    private fun drawText(cs: PDPageContentStream, g: PageGeom, f: PDFont, x: Float, y: Float, text: String, size: Float, color: Int) {
        val baseY = y + size * 0.85f
        cs.saveGraphicsState()
        cs.transform(Matrix.getRotateInstance(Math.toRadians(g.rot.toDouble()), g.x(x, baseY), g.y(x, baseY)))
        cs.beginText()
        cs.setFont(f, size)
        cs.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
        cs.newLineAtOffset(0f, 0f)
        val lines = text.split('\n')
        for ((i, line) in lines.withIndex()) {
            if (i > 0) cs.newLineAtOffset(0f, -size * 1.2f)
            cs.showText(safe(f, line))
        }
        cs.endText()
        cs.restoreGraphicsState()
    }
}
