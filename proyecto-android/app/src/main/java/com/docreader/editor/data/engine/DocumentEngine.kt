package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri

/** Estado en memoria de un documento abierto. `model` guarda el modelo editable propio del formato. */
class DocumentHandle(val uri: Uri, val extension: String, var text: String, val model: Any? = null)

interface DocumentEngine {
    val supportedExtensions: Set<String>
    val supportsEditing: Boolean
    val requiresOcr: Boolean

    suspend fun open(context: Context, uri: Uri, extension: String): DocumentHandle
    suspend fun extractText(context: Context, handle: DocumentHandle): String
    suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean
}
