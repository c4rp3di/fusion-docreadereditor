package com.docreader.editor.ui.viewer

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Imprime un archivo PDF ya generado. */
class FilePrintAdapter(private val file: File, private val title: String) : PrintDocumentAdapter() {
    override fun onLayout(
        old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
        cb: LayoutResultCallback, extras: Bundle?
    ) {
        if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
        cb.onLayoutFinished(
            PrintDocumentInfo.Builder(title)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(),
            true
        )
    }

    override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
        try {
            FileInputStream(file).use { i -> FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) } }
            cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            cb.onWriteFailed(e.message)
        }
    }
}
