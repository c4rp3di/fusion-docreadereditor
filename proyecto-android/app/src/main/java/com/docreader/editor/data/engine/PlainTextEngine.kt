package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** TXT, MD, CSV, TSV, JSON: se editan como texto plano UTF-8. */
class PlainTextEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("txt", "md", "markdown", "json", "log", "xml")
    override val supportsEditing = true
    override val requiresOcr = false

    override suspend fun open(context: Context, uri: Uri, extension: String) = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw java.io.IOException("No se pudo abrir el archivo")
        DocumentHandle(uri, extension, text)
    }

    override suspend fun extractText(context: Context, handle: DocumentHandle) = handle.text

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
