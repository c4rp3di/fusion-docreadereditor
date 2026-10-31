package com.docreader.editor.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val extractedText: String?,
    val lastOpened: Long,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false
)
