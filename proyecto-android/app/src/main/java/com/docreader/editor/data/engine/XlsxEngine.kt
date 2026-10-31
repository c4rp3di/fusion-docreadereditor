package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri
import com.docreader.editor.data.xlsx.XlsxDoc
import com.docreader.editor.data.xlsx.XlsxParser
import com.docreader.editor.data.xlsx.XlsxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * XLSX editable: fórmulas con cálculo, formato de celda, filas/columnas, ordenar, copiar/pegar.
 * Solo se reescriben las hojas modificadas; el resto del paquete se conserva.
 */
class XlsxEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("xlsx")
    override val supportsEditing = true
    override val requiresOcr = false

    override suspend fun open(context: Context, uri: Uri, extension: String) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("No se pudo abrir el archivo")
        val doc = XlsxParser.parse(bytes)
        DocumentHandle(uri, extension, doc.plainText(), doc)
    }

    override suspend fun extractText(context: Context, handle: DocumentHandle) = handle.text

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean {
        return try {
            val doc = handle.model as XlsxDoc
            val out = XlsxWriter.write(doc) // en el hilo de UI: el modelo se edita ahí
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(outputUri, "wt")?.use { it.write(out) } != null
            }
        } catch (e: Exception) {
            false
        }
    }
}
