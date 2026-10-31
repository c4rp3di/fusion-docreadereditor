package com.docreader.editor.ui.viewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.databinding.ItemPdfPageBinding

class PdfPagesAdapter(private val c: PdfEditorController) : RecyclerView.Adapter<PdfPagesAdapter.VH>() {

    class VH(val b: ItemPdfPageBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = c.pageCount()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = h.b.root
        val (w, hh) = c.pageSize(position)
        v.pageW = w
        v.pageH = hh
        v.pageIndex = position
        v.ctl = c
        v.bitmap = c.cachedBitmap(position)
        v.requestLayout()
        v.invalidate()
        c.onPageBound(v)
    }
}
