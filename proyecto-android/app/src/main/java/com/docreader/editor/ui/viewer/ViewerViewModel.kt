package com.docreader.editor.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docreader.editor.data.docx.DocxDoc
import com.docreader.editor.data.engine.DocumentEngine
import com.docreader.editor.data.engine.DocumentHandle
import com.docreader.editor.data.engine.EngineRegistry
import com.docreader.editor.data.pdf.PdfPasswordException
import com.docreader.editor.data.pdf.PdfSession
import com.docreader.editor.data.repository.Backups
import com.docreader.editor.data.repository.DocumentRepository
import com.docreader.editor.data.repository.DraftStore
import com.docreader.editor.data.xlsx.XlsxDoc
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface ViewerState {
    object Loading : ViewerState
    data class Text(val ext: String, val editable: Boolean) : ViewerState
    object Docx : ViewerState
    object Xlsx : ViewerState
    data class Pdf(val uri: Uri) : ViewerState
    object PdfPassword : ViewerState
    data class DraftFound(val time: Long) : ViewerState
    data class LargeFile(val mb: Long) : ViewerState
    data class Error(val message: String) : ViewerState
}

@HiltViewModel
class ViewerViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val registry: EngineRegistry,
    private val repo: DocumentRepository,
    saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state

    /** Texto en edición (formatos de texto); vive en el ViewModel y sobrevive a la rotación. */
    var draft: String = ""
    var ext: String = ""
    var name: String = "documento"
    /** true si se abrió un borrador recuperado (hay cambios sin guardar en el original). */
    var recovered = false
    private var engine: DocumentEngine? = null
    private var handle: DocumentHandle? = null
    private var srcUri: Uri? = null
    private var draftResolved = false
    private var largeConfirmed = false

    val docx: DocxDoc? get() = handle?.model as? DocxDoc
    val pdf: PdfSession? get() = handle?.model as? PdfSession
    private var pdfUri: Uri? = null
    val xlsx: XlsxDoc? get() = handle?.model as? XlsxDoc

    init {
        val uriStr = saved.get<String>("uri")
        if (uriStr == null) _state.value = ViewerState.Error("Documento no especificado")
        else load(Uri.parse(uriStr))
    }

    private fun load(uri: Uri) {
        srcUri = uri
        viewModelScope.launch {
            try {
                name = repo.displayName(uri)
                ext = repo.extensionOf(uri)
                if (ext == "docm") {
                    _state.value = ViewerState.Error("Los documentos con macros (.docm) no están soportados")
                    return@launch
                }
                if (ext != "pdf" && !draftResolved) {
                    val d = DraftStore.find(ctx, uri)
                    if (d != null) { _state.value = ViewerState.DraftFound(d.lastModified()); return@launch }
                }
                val size = repo.sizeOf(uri)
                if (size > LARGE_BYTES && !largeConfirmed) {
                    _state.value = ViewerState.LargeFile(size / 1_048_576)
                    return@launch
                }
                repo.touch(uri)
                openNormal(uri, uri)
            } catch (ex: Exception) {
                _state.value = ViewerState.Error("Archivo no válido o ilegible")
            }
        }
    }

    fun confirmLarge() { largeConfirmed = true; srcUri?.let { load(it) } }

    fun resolveDraft(recover: Boolean) {
        draftResolved = true
        val uri = srcUri ?: return
        if (recover) {
            val f = DraftStore.find(ctx, uri)
            if (f != null) {
                viewModelScope.launch {
                    try {
                        name = repo.displayName(uri)
                        ext = repo.extensionOf(uri)
                        openNormal(uri, Uri.fromFile(f))
                        recovered = true
                    } catch (e: Exception) {
                        _state.value = ViewerState.Error("No se pudo recuperar el borrador")
                    }
                }
                return
            }
        } else DraftStore.clear(ctx, uri)
        load(uri)
    }

    fun discardDraft() { srcUri?.let { DraftStore.clear(ctx, it) } }

    private suspend fun openNormal(uri: Uri, source: Uri) {
        if (ext == "pdf") { openPdf(uri, null); return }
        val e = registry.forExtension(ext)
        val h = e.open(ctx, source, ext)
        engine = e
        handle = if (source == uri) h else DocumentHandle(uri, ext, h.text, h.model)
        draft = h.text
        _state.value = when (h.model) {
            is DocxDoc -> ViewerState.Docx
            is XlsxDoc -> ViewerState.Xlsx
            else -> ViewerState.Text(ext, e.supportsEditing)
        }
    }

    fun retryPdf(password: String) { pdfUri?.let { openPdf(it, password) } }

    private fun openPdf(uri: Uri, password: String?) {
        pdfUri = uri
        viewModelScope.launch {
            _state.value = ViewerState.Loading
            try {
                val s = PdfSession.create(ctx, uri, password)
                engine = registry.forExtension("pdf")
                handle = DocumentHandle(uri, "pdf", "", s)
                _state.value = ViewerState.Pdf(uri)
            } catch (e: PdfPasswordException) {
                _state.value = ViewerState.PdfPassword
            } catch (e: Exception) {
                _state.value = ViewerState.Error("Archivo no válido o ilegible")
            }
        }
    }

    /** Guarda en el archivo original (outputUri == null) o en el Uri dado. */
    suspend fun save(outputUri: Uri?): Boolean {
        val e = engine ?: return false
        val h = handle ?: return false
        if (h.model == null) h.text = draft
        if (outputUri == null && ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("backup", true)) {
            Backups.create(ctx, h.uri, name)
        }
        val ok = e.save(ctx, h, outputUri ?: h.uri)
        if (ok) { srcUri?.let { DraftStore.clear(ctx, it) }; recovered = false }
        return ok
    }

    /** Guarda un borrador automático (no toca el original). No se aplica a PDF. */
    fun saveDraft() {
        val e = engine ?: return
        val h = handle ?: return
        if (ext == "pdf") return
        val f = DraftStore.fileFor(ctx, srcUri ?: h.uri, ext)
        viewModelScope.launch {
            try {
                if (h.model == null) h.text = draft
                if (!e.save(ctx, h, Uri.fromFile(f))) f.delete()
            } catch (_: Exception) { f.delete() }
        }
    }

    /** Exporta el estado actual a la caché (para compartir/imprimir). */
    suspend fun exportToCache(): File? {
        val e = engine ?: return null
        val h = handle ?: return null
        val dir = File(ctx.cacheDir, "share")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "documento.$ext" })
        if (h.model == null) h.text = draft
        return if (e.save(ctx, h, Uri.fromFile(f))) f else null
    }

    fun mime(): String = when (ext) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        else -> "text/plain"
    }

    fun suggestedName(): String {
        val base = name.substringBeforeLast('.', name)
        return "${base}_editado.${ext.ifEmpty { "txt" }}"
    }

    override fun onCleared() {
        pdf?.destroy()
        super.onCleared()
    }

    companion object { private const val LARGE_BYTES = 30L * 1024 * 1024 }
}
