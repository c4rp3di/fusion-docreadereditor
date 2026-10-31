package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import javax.inject.Inject

/** HTML: se edita el código fuente tal cual (no se pierden etiquetas) y se puede ver renderizado. */
class HtmlEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("html", "htm")
    override val supportsEditing = true
    override val requiresOcr = false

    override suspend fun open(context: Context, uri: Uri, extension: String) = withContext(Dispatchers.IO) {
        val html = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw java.io.IOException("No se pudo abrir el archivo")
        DocumentHandle(uri, extension, html)
    }

    /** Texto limpio para el buscador de la biblioteca. */
    override suspend fun extractText(context: Context, handle: DocumentHandle): String =
        withContext(Dispatchers.Default) { try { Jsoup.parse(handle.text).text() } catch (e: Exception) { handle.text } }

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(outputUri, "wt")?.use {
                    it.write(handle.text.toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Exception) {
                false
            }
        }
}
