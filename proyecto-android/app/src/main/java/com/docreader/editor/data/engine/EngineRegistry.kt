package com.docreader.editor.data.engine

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineRegistry @Inject constructor(
    private val plain: PlainTextEngine,
    html: HtmlEngine,
    docx: DocxEngine,
    xlsx: XlsxEngine,
    pdf: PdfEngine,
    csv: CsvEngine
) {
    private val engines: List<DocumentEngine> = listOf(plain, html, docx, xlsx, pdf, csv)

    /** Extensiones desconocidas se tratan como texto plano. */
    fun forExtension(ext: String): DocumentEngine =
        engines.firstOrNull { ext.lowercase() in it.supportedExtensions } ?: plain
}
