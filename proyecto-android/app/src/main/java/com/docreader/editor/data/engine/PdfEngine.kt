package com.docreader.editor.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.docreader.editor.data.pdf.PdfSession
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PDF: texto nativo con PDFBox; si el PDF no tiene texto (escaneado) se usa OCR ML Kit para indexarlo.
 * El visor y la edición los gestiona PdfSession (el modelo del handle).
 */
class PdfEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("pdf")
    override val supportsEditing = true
    override val requiresOcr = false

    override suspend fun open(context: Context, uri: Uri, extension: String) =
        DocumentHandle(uri, extension, "", PdfSession.create(context, uri, null))

    override suspend fun extractText(context: Context, handle: DocumentHandle): String =
        withContext(Dispatchers.IO) {
            val native = try {
                val tmp = java.io.File.createTempFile("idx", ".pdf", context.cacheDir)
                context.contentResolver.openInputStream(handle.uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val d = PDDocument.load(tmp, MemoryUsageSetting.setupTempFileOnly())
                try {
                    val t = PDFTextStripper().getText(d)
                    val pages = maxOf(1, d.numberOfPages)
                    if (t.trim().length >= 20 * minOf(pages, 5)) t else null
                } finally {
                    d.close(); tmp.delete()
                }
            } catch (e: Exception) { null }
            native ?: ocrAll(context, handle.uri)
        }

    private suspend fun ocrAll(context: Context, uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val sb = StringBuilder()
        try {
            pfd.use {
                PdfRenderer(it).use { renderer ->
                    for (i in 0 until minOf(renderer.pageCount, MAX_OCR_PAGES)) {
                        renderer.openPage(i).use { page ->
                            val scale = 200f / 72f
                            val w = (page.width * scale).toInt().coerceAtMost(2600)
                            val h = (w.toFloat() / page.width * page.height).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            sb.append(recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text).append('\n')
                            bmp.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            recognizer.close()
        }
        return sb.toString()
    }

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean {
        val s = handle.model as? PdfSession ?: return false
        return try {
            s.commit()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(outputUri, "wt")?.use { o ->
                    s.work.inputStream().use { it.copyTo(o) }
                } != null
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object { const val MAX_OCR_PAGES = 30 }
}
