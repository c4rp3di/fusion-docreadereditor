package com.docreader.editor.ui.list

object DocType {
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Grupo para filtrar: word, excel, pdf o text. */
    fun of(name: String): String = when (ext(name)) {
        "docx" -> "word"
        "xlsx", "csv", "tsv" -> "excel"
        "pdf" -> "pdf"
        else -> "text"
    }

    fun icon(name: String): String = when (ext(name)) {
        "docx" -> "📄"
        "xlsx", "csv", "tsv" -> "📊"
        "pdf" -> "📕"
        "md", "markdown", "txt", "log" -> "📝"
        "json", "xml" -> "🧩"
        "html", "htm" -> "🌐"
        else -> "📃"
    }
}
