package com.docreader.editor.ui.list

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docreader.editor.data.db.DocumentEntity
import com.docreader.editor.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentListViewModel @Inject constructor(
    private val repo: DocumentRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    val filter = MutableStateFlow("all")
    val sort = MutableStateFlow("recent")

    @OptIn(ExperimentalCoroutinesApi::class)
    val docs: StateFlow<List<DocumentEntity>> =
        combine(query.flatMapLatest { repo.documents(it) }, filter, sort) { list, f, s ->
            val fl = list.filter {
                when (f) {
                    "all" -> true
                    "fav" -> it.favorite
                    else -> DocType.of(it.displayName) == f
                }
            }
            when (s) {
                "name" -> fl.sortedBy { it.displayName.lowercase() }
                "size" -> fl.sortedByDescending { it.sizeBytes }
                else -> fl.sortedByDescending { it.lastOpened }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) { query.value = q }

    fun import(uri: Uri) = viewModelScope.launch {
        repo.import(uri)
        repo.indexText(uri)
    }

    fun importTree(tree: Uri, onDone: (Int) -> Unit) = viewModelScope.launch {
        onDone(try { repo.importTree(tree) } catch (e: Exception) { 0 })
    }

    /** Crea un documento en blanco en el Uri elegido, lo añade a la biblioteca y avisa del resultado. */
    fun createNew(kind: String, uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = repo.createNew(kind, uri)
        if (ok) {
            try { repo.import(uri); repo.indexText(uri) } catch (_: Exception) { }
        }
        onDone(ok)
    }

    fun toggleFavorite(doc: DocumentEntity) = viewModelScope.launch { repo.setFavorite(doc.uri, !doc.favorite) }

    fun delete(uri: String) = viewModelScope.launch { repo.remove(uri) }
}
