package com.docreader.editor.data.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Edición "quirúrgica" de paquetes OOXML: se reescribe el ZIP copiando byte a byte todas las
 * entradas (imágenes, estilos, temas...) salvo las que se reemplazan. Sin dependencias externas.
 */
internal object OoxmlZip {
    fun read(bytes: ByteArray, accept: (String) -> Boolean): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory && accept(e.name)) out[e.name] = zin.readBytes()
                e = zin.nextEntry
            }
        }
        return out
    }

    fun rewrite(
        bytes: ByteArray,
        replace: Map<String, ByteArray>,
        drop: Set<String> = emptySet(),
        add: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { out ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (e.name !in drop) {
                        out.putNextEntry(ZipEntry(e.name))
                        if (!e.isDirectory) out.write(replace[e.name] ?: zin.readBytes())
                        out.closeEntry()
                    }
                    e = zin.nextEntry
                }
            }
            for ((name, data) in add) {
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c.code < 0x20 && c != '\n' && c != '\t' -> Unit
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    fun attr(tag: String, name: String): String? =
        Regex("(?:^|[\\s:<])" + Regex.escape(name) + "=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
}
