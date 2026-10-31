package com.docreader.editor.data.pdf

import com.docreader.editor.data.docx.DocxWriter
import com.docreader.editor.data.engine.OoxmlZip
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Crea un DOCX (con estilos de título) desde párrafos de texto; una lista por página con salto entre ellas. */
object DocxBuilder {
    fun build(pages: List<List<String>>): ByteArray {
        val body = StringBuilder()
        pages.forEachIndexed { i, paras ->
            if (i > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            if (paras.isEmpty()) body.append("<w:p/>")
            for (p in paras) {
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(OoxmlZip.escape(p)).append("</w:t></w:r></w:p>")
            }
        }
        val xmlHead = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        val doc = xmlHead + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1417\" w:left=\"1417\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        val styles = xmlHead + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            DocxWriter.styleDefs.values.joinToString("") + "</w:styles>"
        val types = xmlHead + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>"
        val rels = xmlHead + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val docRels = xmlHead + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", types)
            put("_rels/.rels", rels)
            put("word/document.xml", doc)
            put("word/styles.xml", styles)
            put("word/_rels/document.xml.rels", docRels)
        }
        return bos.toByteArray()
    }
}
