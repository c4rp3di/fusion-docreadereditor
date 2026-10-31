package com.docreader.editor.ui.list

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.data.db.DocumentEntity
import com.docreader.editor.databinding.ItemDocumentBinding

class DocumentAdapter(
    private val onClick: (DocumentEntity) -> Unit,
    private val onLongClick: (DocumentEntity) -> Unit
) : ListAdapter<DocumentEntity, DocumentAdapter.VH>(Diff) {

    class VH(val b: ItemDocumentBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = getItem(position)
        val ctx = holder.b.root.context
        holder.b.icon.text = DocType.icon(d.displayName)
        holder.b.name.text = d.displayName
        holder.b.meta.text = Formatter.formatShortFileSize(ctx, d.sizeBytes) + " · " +
            DateUtils.getRelativeTimeSpanString(d.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        holder.b.fav.visibility = if (d.favorite) View.VISIBLE else View.GONE
        holder.b.root.setOnClickListener { onClick(d) }
        holder.b.root.setOnLongClickListener { onLongClick(d); true }
    }

    private object Diff : DiffUtil.ItemCallback<DocumentEntity>() {
        override fun areItemsTheSame(a: DocumentEntity, b: DocumentEntity) = a.uri == b.uri
        override fun areContentsTheSame(a: DocumentEntity, b: DocumentEntity) = a == b
    }
}
