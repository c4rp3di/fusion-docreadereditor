package com.docreader.editor.ui.viewer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import com.docreader.editor.ui.ShortcutHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.docreader.editor.R
import com.docreader.editor.databinding.FragmentViewerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class DocumentViewerFragment : Fragment(), ShortcutHandler {
    private var _b: FragmentViewerBinding? = null
    private val b get() = _b!!
    private val vm: ViewerViewModel by viewModels()

    private var pdfController: PdfEditorController? = null
    private var pendingKind = ""
    private var textController: TextEditorController? = null
    private var docxController: DocxEditorController? = null
    private var xlsxController: XlsxEditorController? = null
    private var pagePreview: PagePreviewController? = null
    private var pagePreviewing = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val img = withContext(Dispatchers.IO) { DocxEditorController.decodeImage(requireContext(), uri) }
            if (img == null) toast("No se pudo leer la imagen") else docxController?.insertImage(img)
        }
    }

    private val pickPdfFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pdfController?.mergeFrom(it) }
    }

    private var previewPagesToExport: List<PreviewPage> = emptyList()

    private val createTarget = registerForActivityResult(CreateDocAs()) { uri ->
        uri ?: return@registerForActivityResult
        if (pendingKind == "previewpdf") exportPreviewPdf(uri) else pdfController?.onTarget(pendingKind, uri)
    }

    private val saveAs = registerForActivityResult(CreateDocAs()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = vm.save(uri)
            toast(if (ok) "Guardado" else "No se pudo guardar")
            if (ok) { markSaved(); pdfController?.onSaved() }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentViewerBinding.inflate(i, c, false)
        return b.root
    }

    private var stateDialog: AlertDialog? = null

    fun isDirty(): Boolean = vm.recovered || textController?.dirty() == true || docxController?.dirty() == true ||
        xlsxController?.dirty() == true || pdfController?.dirty() == true

    private fun markSaved() {
        textController?.markSaved(); docxController?.markSaved(); xlsxController?.markSaved(); pdfController?.markSaved()
        vm.recovered = false
    }

    private fun leave() { findNavController().popBackStack() }

    private var printWeb: android.webkit.WebView? = null

    private fun saveNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (vm.save(null)) { toast("Guardado"); markSaved(); pdfController?.onSaved() }
            else saveAs.launch(vm.mime() to vm.suggestedName())
        }
    }

    override fun onShortcut(keyCode: Int): Boolean {
        if (pagePreviewing) return false
        if (keyCode == KeyEvent.KEYCODE_S) { saveNow(); return true }
        return docxController?.shortcut(keyCode) ?: xlsxController?.shortcut(keyCode)
            ?: textController?.shortcut(keyCode) ?: pdfController?.shortcut(keyCode) ?: false
    }

    // ------------------------------------------------------------ menú «Más»: compartir, imprimir, exportar
    private fun moreMenu(anchor: View) {
        val items = ArrayList<Pair<String, String>>()
        items.add("Compartir…" to "share")
        if (vm.ext in setOf("pdf", "docx", "xlsx", "html", "htm")) items.add("Imprimir…" to "print")
        if (vm.ext == "docx" || vm.ext == "xlsx") items.add("Exportar a PDF…" to "pdf")
        items.add("Información" to "info")
        val m = android.widget.PopupMenu(requireContext(), anchor)
        items.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            when (items[it.itemId].second) {
                "share" -> shareCurrent()
                "print" -> printCurrent()
                "pdf" -> exportPdf()
                else -> AlertDialog.Builder(requireContext()).setTitle(vm.name)
                    .setMessage("Tipo: ${vm.ext.uppercase()}\nCambios sin guardar: ${if (isDirty()) "sí" else "no"}")
                    .setPositiveButton("Cerrar", null).show()
            }
            true
        }
        m.show()
    }

    private fun shareCurrent() {
        viewLifecycleOwner.lifecycleScope.launch {
            val f = try { vm.exportToCache() } catch (e: Exception) { null }
            if (f == null) { toast("No se pudo preparar el archivo"); return@launch }
            if (vm.ext == "pdf") pdfController?.reloadView()
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).setType(vm.mime()).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Compartir"))
        }
    }

    private suspend fun currentPages(): List<PreviewPage>? {
        vm.docx?.let { d ->
            val blocks = d.snapshot()
            return withContext(Dispatchers.Default) { DocxPaginator(d, blocks).paginate() }
        }
        xlsxController?.let { x -> return x.doc.sheets.flatMap { SheetPaginator.paginate(x.doc, it) } }
        return null
    }

    private fun exportPdf() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pages = try { currentPages() } catch (e: Exception) { null }
            if (pages == null) { toast("No se pudo calcular las páginas"); return@launch }
            previewPagesToExport = pages
            pendingKind = "previewpdf"
            createTarget.launch("application/pdf" to vm.name.substringBeforeLast('.') + ".pdf")
        }
    }

    private fun printCurrent() {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (vm.ext) {
                    "html", "htm" -> {
                        val wv = android.webkit.WebView(ctx)
                        printWeb = wv
                        wv.settings.javaScriptEnabled = false
                        wv.settings.blockNetworkLoads = true
                        wv.webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(v: android.webkit.WebView, url: String?) {
                                pm.print(vm.name, v.createPrintDocumentAdapter(vm.name), null)
                            }
                        }
                        wv.loadDataWithBaseURL(null, vm.draft, "text/html", "UTF-8", null)
                    }
                    "pdf" -> {
                        val f = vm.exportToCache()
                        if (f == null) { toast("No se pudo preparar el archivo"); return@launch }
                        pdfController?.reloadView()
                        pm.print(vm.name, FilePrintAdapter(f, vm.name), null)
                    }
                    else -> {
                        val pages = currentPages()
                        if (pages == null) { toast("No se pudo calcular las páginas"); return@launch }
                        val bytes = withContext(Dispatchers.Default) { pagesToPdf(pages) }
                        val f = java.io.File(ctx.cacheDir, "share/impresion.pdf")
                        f.parentFile?.mkdirs()
                        f.writeBytes(bytes)
                        pm.print(vm.name, FilePrintAdapter(f, vm.name), null)
                    }
                }
            } catch (e: Exception) {
                toast("No se pudo imprimir: ${e.message ?: "error"}")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isDirty()) vm.saveDraft()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Aviso de cambios sin guardar al volver atrás
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pagePreviewing) { pagePreview?.hide(); return }
                if (!isDirty()) { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); return }
                AlertDialog.Builder(requireContext()).setTitle("Cambios sin guardar")
                    .setMessage("¿Quieres guardar los cambios antes de salir?")
                    .setPositiveButton("Guardar") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (vm.save(null)) { markSaved(); pdfController?.onSaved(); leave() }
                            else { toast("No se pudo guardar en el original: usa «Guardar como»"); saveAs.launch(vm.mime() to vm.suggestedName()) }
                        }
                    }
                    .setNegativeButton("Descartar") { _, _ -> vm.discardDraft(); markSaved(); leave() }
                    .setNeutralButton("Seguir editando", null).show()
            }
        })
        // Borrador automático cada 30 s mientras haya cambios
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { delay(30_000); if (isDirty()) vm.saveDraft() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
        b.btnSave.setOnClickListener { saveNow() }
        b.btnMore.setOnClickListener { moreMenu(it) }
    }

    private fun render(s: ViewerState) {
        if (pagePreviewing) return
        b.progress.isVisible = s is ViewerState.Loading
        b.bar.isVisible = s is ViewerState.Text || s is ViewerState.Docx || s is ViewerState.Xlsx || s is ViewerState.Pdf
        b.error.isVisible = s is ViewerState.Error
        b.textScroll.isVisible = s is ViewerState.Text && (textController?.isEditMode() ?: true)
        b.textBar.root.isVisible = s is ViewerState.Text
        b.docxList.isVisible = s is ViewerState.Docx
        b.docxToolbar.root.isVisible = s is ViewerState.Docx
        b.gridScroll.isVisible = s is ViewerState.Xlsx
        b.xlsxToolbar.root.isVisible = s is ViewerState.Xlsx
        b.fxBar.isVisible = s is ViewerState.Xlsx
        b.tabsScroll.isVisible = s is ViewerState.Xlsx && vm.xlsx?.isCsv != true
        b.pdfScroll.isVisible = s is ViewerState.Pdf
        b.pdfToolbar.root.isVisible = s is ViewerState.Pdf
        when (s) {
            is ViewerState.Text -> showText(s)
            is ViewerState.Docx -> showDocx()
            is ViewerState.Xlsx -> showXlsx()
            is ViewerState.Pdf -> showPdf()
            ViewerState.PdfPassword -> passwordDialog()
            is ViewerState.DraftFound -> draftDialog(s.time)
            is ViewerState.LargeFile -> largeDialog(s.mb)
            is ViewerState.Error -> b.error.text = s.message
            ViewerState.Loading -> Unit
        }
    }

    private fun showText(s: ViewerState.Text) {
        if (textController != null) return
        b.btnPreview.isVisible = false
        textController = TextEditorController(requireContext(), b, vm, s.ext, s.editable)
        if (!s.editable) b.btnSave.isVisible = false
    }

    private fun showDocx() {
        if (docxController != null) return
        val d = vm.docx ?: return
        docxController = DocxEditorController(
            requireContext(), b.docxList, b.docxToolbar, d,
            { pickImage.launch("image/*") },
            { showDocxPreview() }
        )
    }

    private fun showDocxPreview() {
        val d = vm.docx ?: return
        val blocks = d.snapshot()
        toast("Calculando páginas…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.Default) { DocxPaginator(d, blocks).paginate() }
                openPreview("Vista de páginas (aproximada)", pages)
            } catch (e: Exception) {
                toast("No se pudo calcular la paginación")
            }
        }
    }

    private fun showXlsxPreview() {
        val x = xlsxController ?: return
        try {
            val sh = x.currentSheet()
            openPreview("Impresión · ${sh.name}", SheetPaginator.paginate(x.doc, sh))
        } catch (e: Exception) {
            toast("No se pudo calcular la vista de impresión")
        }
    }

    private fun exportPreviewPdf(uri: android.net.Uri) {
        val pages = previewPagesToExport
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { pagesToPdf(pages) }
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                }
                toast("PDF exportado")
            } catch (e: Exception) {
                toast("No se pudo exportar: ${e.message ?: "error"}")
            }
        }
    }

    private fun openPreview(title: String, pages: List<PreviewPage>) {
        pagePreviewing = true
        listOf<View>(b.docxToolbar.root, b.docxList, b.xlsxToolbar.root, b.fxBar, b.gridScroll, b.tabsScroll)
            .forEach { it.isVisible = false }
        pagePreview = PagePreviewController(requireContext(), b, { pgs ->
            previewPagesToExport = pgs
            pendingKind = "previewpdf"
            createTarget.launch("application/pdf" to "documento.pdf")
        }) {
            pagePreviewing = false
            pagePreview = null
            render(vm.state.value)
        }.also { it.show(title, pages) }
    }

    private fun showXlsx() {
        if (xlsxController != null) return
        val d = vm.xlsx ?: return
        xlsxController = XlsxEditorController(requireContext(), b, d) { showXlsxPreview() }
    }

    private fun showPdf() {
        if (pdfController != null) return
        val sess = vm.pdf ?: return
        pdfController = PdfEditorController(
            requireContext(), b, sess, viewLifecycleOwner.lifecycleScope,
            { pickPdfFile.launch(arrayOf("application/pdf")) },
            { kind, mime, name -> pendingKind = kind; createTarget.launch(mime to name) }
        )
    }

    private fun draftDialog(time: Long) {
        if (stateDialog?.isShowing == true) return
        val fecha = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
        stateDialog = AlertDialog.Builder(requireContext()).setTitle("Borrador sin guardar")
            .setMessage("Hay una versión de este documento con cambios sin guardar ($fecha). ¿Quieres recuperarla?")
            .setCancelable(false)
            .setPositiveButton("Recuperar") { _, _ -> vm.resolveDraft(true) }
            .setNegativeButton("Abrir el original") { _, _ -> vm.resolveDraft(false) }.show()
    }

    private fun largeDialog(mb: Long) {
        if (stateDialog?.isShowing == true) return
        stateDialog = AlertDialog.Builder(requireContext()).setTitle("Archivo grande")
            .setMessage("El archivo pesa $mb MB. Abrirlo puede ser lento o agotar la memoria del dispositivo. ¿Continuar?")
            .setCancelable(false)
            .setPositiveButton("Abrir") { _, _ -> vm.confirmLarge() }
            .setNegativeButton("Cancelar") { _, _ -> leave() }.show()
    }

    private var pwDialog: AlertDialog? = null

    private fun passwordDialog() {
        if (pwDialog?.isShowing == true) return
        val et = EditText(requireContext()).apply {
            hint = "Contraseña"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = android.widget.FrameLayout(requireContext()).apply { setPadding(60, 20, 60, 0); addView(et) }
        pwDialog = AlertDialog.Builder(requireContext()).setTitle("PDF protegido").setView(box).setCancelable(false)
            .setPositiveButton("Abrir") { _, _ -> vm.retryPdf(et.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ ->
                b.error.isVisible = true
                b.error.text = "PDF protegido con contraseña"
            }.show()
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        docxController = null
        textController = null
        xlsxController = null
        pdfController?.close(); pdfController = null
        _b = null
    }
}
