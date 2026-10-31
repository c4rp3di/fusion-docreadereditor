package com.docreader.editor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastOpened DESC")
    fun all(): Flow<List<DocumentEntity>>

    @Query(
        "SELECT * FROM documents WHERE displayName LIKE '%' || :q || '%' " +
            "OR extractedText LIKE '%' || :q || '%' ORDER BY lastOpened DESC"
    )
    fun search(q: String): Flow<List<DocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DocumentEntity)

    @Query("UPDATE documents SET extractedText = :text WHERE uri = :uri")
    suspend fun updateText(uri: String, text: String?)

    @Query("UPDATE documents SET lastOpened = :time WHERE uri = :uri")
    suspend fun touch(uri: String, time: Long)

    @Query("SELECT * FROM documents WHERE uri = :uri")
    suspend fun get(uri: String): DocumentEntity?

    @Query("UPDATE documents SET favorite = :fav WHERE uri = :uri")
    suspend fun setFavorite(uri: String, fav: Boolean)

    @Query("DELETE FROM documents WHERE uri = :uri")
    suspend fun delete(uri: String)
}
