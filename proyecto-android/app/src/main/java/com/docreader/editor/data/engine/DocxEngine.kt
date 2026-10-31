package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri
import com.docreader.editor.data.docx.DocxDoc
import com.docreader.editor.data.docx.DocxParser
import com.docreader.editor.data.docx.DocxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * DOCX con edición de formato (negrita, cursiva, fuentes, listas, tablas, imágenes...).
 * Solo se reescribe lo modificado; el resto del paquete se conserva intacto.
 */
class DocxEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("docx")
    override val supportsEditing = true
    override val requiresOcr = false

    override suspend fun open(context: Context, uri: Uri, extension: String) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("No se pudo abrir el archivo")
        val doc = DocxParser.parse(bytes)
        DocumentHandle(uri, extension, doc.plainText(), doc)
    }

    override suspend fun extractText(context: Context, handle: DocumentHandle) = handle.text

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean {
        return try {
            val doc = handle.model as DocxDoc
            val out = DocxWriter.write(doc) // en el hilo de UI: el modelo se edita ahí
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(outputUri, "wt")?.use { it.write(out) } != null
            }
        } catch (e: Exception) {
            false
        }
    }
}
