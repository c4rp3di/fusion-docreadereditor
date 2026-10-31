package com.docreader.editor.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.docreader.editor.data.db.DocumentDao
import com.docreader.editor.data.db.DocumentEntity
import com.docreader.editor.data.engine.EngineRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: DocumentDao,
    private val registry: EngineRegistry
) {
    fun documents(query: String): Flow<List<DocumentEntity>> =
        if (query.isBlank()) dao.all() else dao.search(query.trim())

    fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment ?: "documento"
    }

    fun extensionOf(uri: Uri): String {
        val e = displayName(uri).substringAfterLast('.', "").lowercase()
        if (e.isNotEmpty() && e.length <= 5) return e
        val mime = ctx.contentResolver.getType(uri)
        return mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: e
    }

    /** Escribe una plantilla vacía del tipo pedido en el Uri creado por el usuario. */
    suspend fun createNew(kind: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes: ByteArray = when (kind) {
            "docx" -> com.docreader.editor.data.pdf.DocxBuilder.build(listOf(listOf("")))
            "xlsx" -> com.docreader.editor.data.xlsx.XlsxBuilder.blank()
            "md" -> "# Título\n\n".toByteArray()
            "csv" -> "Columna1,Columna2,Columna3\n".toByteArray()
            else -> ByteArray(0)
        }
        try {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
        } catch (e: Exception) {
            false
        }
    }

    fun sizeOf(uri: Uri): Long {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && !c.isNull(i)) return c.getLong(i)
            }
        }
        return 0L
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
        }
        val now = System.currentTimeMillis()
        val old = dao.get(uri.toString())
        dao.upsert(
            DocumentEntity(
                uri = uri.toString(),
                displayName = displayName(uri),
                mimeType = ctx.contentResolver.getType(uri) ?: "application/octet-stream",
                sizeBytes = sizeOf(uri),
                lastModified = now,
                extractedText = old?.extractedText,
                lastOpened = now,
                favorite = old?.favorite ?: false
            )
        )
    }

    /** Extrae texto (OCR para PDF) y lo guarda para la búsqueda global. */
    suspend fun indexText(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val ext = extensionOf(uri)
            if (ext == "docm") return@withContext
            val engine = registry.forExtension(ext)
            val handle = if (ext == "pdf") com.docreader.editor.data.engine.DocumentHandle(uri, ext, "") else engine.open(ctx, uri, ext)
            dao.updateText(uri.toString(), engine.extractText(ctx, handle))
        } catch (_: Exception) { }
    }

    suspend fun setFavorite(uri: String, fav: Boolean) = dao.setFavorite(uri, fav)

    /** Importa todos los archivos compatibles de una carpeta (hasta 4 niveles y 500 archivos). */
    suspend fun importTree(tree: Uri): Int = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { }
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: return@withContext 0
        val found = ArrayList<Uri>()
        fun walk(d: androidx.documentfile.provider.DocumentFile, depth: Int) {
            if (found.size >= 500 || depth > 4) return
            for (f in d.listFiles()) {
                if (f.isDirectory) walk(f, depth + 1)
                else if ((f.name?.substringAfterLast('.', "") ?: "").lowercase() in SUPPORTED) found.add(f.uri)
                if (found.size >= 500) return
            }
        }
        walk(root, 0)
        for (u in found) { import(u); if (extensionOf(u) != "pdf") indexText(u) }
        found.size
    }

    suspend fun touch(uri: Uri) = dao.touch(uri.toString(), System.currentTimeMillis())
    suspend fun remove(uri: String) = dao.delete(uri)

    companion object {
        val SUPPORTED = setOf("pdf", "docx", "xlsx", "txt", "md", "markdown", "csv", "tsv", "json", "html", "htm", "xml", "log")
    }
}
