package com.docreader.editor.data.xlsx

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Libro de Excel vacío (una hoja) para «Nueva hoja de cálculo». */
object XlsxBuilder {
    fun blank(): ByteArray {
        val h = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        val types = h + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"
        val rels = h + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
        val wb = h + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"Hoja1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val wbRels = h + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        val sheet = h + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", types)
            put("_rels/.rels", rels)
            put("xl/workbook.xml", wb)
            put("xl/_rels/workbook.xml.rels", wbRels)
            put("xl/worksheets/sheet1.xml", sheet)
            put("xl/styles.xml", StyleBook.DEFAULT_XML)
        }
        return bos.toByteArray()
    }
}
