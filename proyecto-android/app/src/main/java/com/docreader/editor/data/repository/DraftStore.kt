package com.docreader.editor.data.repository

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Borradores automáticos (para recuperar trabajo no guardado si la app se cierra). */
object DraftStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "drafts").apply { mkdirs() }

    private fun key(uri: Uri): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    /** Archivo del borrador (conserva la extensión para que el motor correcto lo abra). */
    fun fileFor(ctx: Context, uri: Uri, ext: String): File {
        clear(ctx, uri)
        return File(dir(ctx), key(uri) + "." + ext.ifEmpty { "txt" })
    }

    fun find(ctx: Context, uri: Uri): File? {
        val k = key(uri) + "."
        return dir(ctx).listFiles { f -> f.name.startsWith(k) && f.length() > 0 }?.maxByOrNull { it.lastModified() }
    }

    fun clear(ctx: Context, uri: Uri) {
        val k = key(uri) + "."
        dir(ctx).listFiles { f -> f.name.startsWith(k) }?.forEach { it.delete() }
    }
}
