package com.docreader.editor.data.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copia de seguridad del original antes de sobrescribirlo (se conservan las 3 últimas de cada archivo). */
object Backups {
    private const val MAX_BYTES = 80L * 1024 * 1024
    private fun dir(ctx: Context) = File(ctx.filesDir, "backups").apply { mkdirs() }

    suspend fun create(ctx: Context, uri: Uri, displayName: String) = withContext(Dispatchers.IO) {
        try {
            val d = dir(ctx)
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safe = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val f = File(d, "${ts}_$safe")
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() > MAX_BYTES || f.length() == 0L) f.delete()
            d.listFiles { x -> x.name.endsWith("_$safe") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
            d.listFiles()?.sortedByDescending { it.name }?.drop(40)?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    fun list(ctx: Context): List<File> = dir(ctx).listFiles()?.sortedByDescending { it.name } ?: emptyList()
}
