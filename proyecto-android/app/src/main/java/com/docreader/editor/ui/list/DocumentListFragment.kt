package com.docreader.editor.ui.list

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.docreader.editor.R
import com.docreader.editor.data.db.DocumentEntity
import com.docreader.editor.data.repository.Backups
import com.docreader.editor.databinding.FragmentListBinding
import com.docreader.editor.ui.viewer.CreateDocAs
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

/** OpenDocument que además pide permiso de escritura persistente. */
class OpenDocWritable : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

@AndroidEntryPoint
class DocumentListFragment : Fragment() {
    private var _b: FragmentListBinding? = null
    private val b get() = _b!!
    private val vm: DocumentListViewModel by viewModels()
    private var pendingKind = ""

    private val picker = registerForActivityResult(OpenDocWritable()) { uri ->
        uri?.let { vm.import(it) }
    }

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.importTree(it) { n -> if (isAdded) toast("$n archivo(s) importados") } }
    }

    private val createDoc = registerForActivityResult(CreateDocAs()) { uri ->
        uri ?: return@registerForActivityResult
        vm.createNew(pendingKind, uri) { ok ->
            if (!isAdded) return@createNew
            if (ok) openUri(uri.toString()) else toast("No se pudo crear el documento")
        }
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
    private fun openUri(u: String) = findNavController().navigate(R.id.action_list_to_viewer, bundleOf("uri" to u))
    private val prefs get() = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = DocumentAdapter(onClick = { openUri(it.uri) }, onLongClick = { docMenu(it) })
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.fab.setOnClickListener { fabMenu(it) }
        b.btnSettings.setOnClickListener { settings() }
        b.search.doAfterTextChanged { vm.setQuery(it?.toString().orEmpty()) }
        buildChips()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.docs.collect {
                    adapter.submitList(it)
                    b.empty.isVisible = it.isEmpty()
                }
            }
        }
    }

    // ------------------------------------------------------------ filtros y orden
    private fun sortLabel() = when (vm.sort.value) { "name" -> "Orden: nombre ▾"; "size" -> "Orden: tamaño ▾"; else -> "Orden: recientes ▾" }

    private fun buildChips() {
        val filters = listOf("all" to "Todos", "fav" to "⭐ Favoritos", "word" to "Word", "excel" to "Excel", "pdf" to "PDF", "text" to "Texto")
        for ((k, label) in filters) {
            b.chips.addView(Chip(requireContext()).apply {
                text = label; isCheckable = true; tag = k; id = View.generateViewId(); isChecked = k == vm.filter.value
            })
        }
        b.chips.setOnCheckedStateChangeListener { group, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            vm.filter.value = group.findViewById<Chip>(id).tag as String
        }
        val sortChip = Chip(requireContext()).apply { text = sortLabel(); isCheckable = false }
        sortChip.setOnClickListener { v ->
            val opts = listOf("Recientes" to "recent", "Nombre" to "name", "Tamaño" to "size")
            val m = PopupMenu(requireContext(), v)
            opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
            m.setOnMenuItemClickListener { vm.sort.value = opts[it.itemId].second; sortChip.text = sortLabel(); true }
            m.show()
        }
        b.chips.addView(sortChip)
    }

    // ------------------------------------------------------------ menús
    private fun docMenu(doc: DocumentEntity) {
        AlertDialog.Builder(requireContext()).setTitle(doc.displayName)
            .setItems(arrayOf("Abrir", if (doc.favorite) "Quitar de favoritos" else "Añadir a favoritos", "Compartir", "Quitar de la biblioteca")) { _, i ->
                when (i) {
                    0 -> openUri(doc.uri)
                    1 -> vm.toggleFavorite(doc)
                    2 -> share(doc)
                    else -> AlertDialog.Builder(requireContext()).setTitle(doc.displayName)
                        .setMessage("¿Quitar de la biblioteca? (el archivo no se borra)")
                        .setPositiveButton("Quitar") { _, _ -> vm.delete(doc.uri) }
                        .setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    private fun share(doc: DocumentEntity) {
        try {
            val intent = Intent(Intent.ACTION_SEND).setType(doc.mimeType)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(doc.uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Compartir"))
        } catch (e: Exception) { toast("No se pudo compartir") }
    }

    private fun fabMenu(anchor: View) {
        val opts = listOf(
            Triple("Importar archivo…", "import", ""),
            Triple("Importar carpeta…", "tree", ""),
            Triple("Nuevo documento Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Triple("Nueva hoja de Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Triple("Nuevo texto (.txt)", "txt", "text/plain"),
            Triple("Nuevo Markdown (.md)", "md", "text/markdown"),
            Triple("Nueva tabla CSV", "csv", "text/csv")
        )
        val m = PopupMenu(requireContext(), anchor)
        opts.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            val (_, kind, mime) = opts[it.itemId]
            when (kind) {
                "import" -> picker.launch(arrayOf("*/*"))
                "tree" -> pickTree.launch(null)
                else -> { pendingKind = kind; createDoc.launch(mime to "Nuevo.$kind") }
            }
            true
        }
        m.show()
    }

    // ------------------------------------------------------------ ajustes
    private fun onOff(k: String, d: Boolean) = if (prefs.getBoolean(k, d)) "activado" else "desactivado"

    private fun toggle(k: String, d: Boolean) {
        prefs.edit().putBoolean(k, !prefs.getBoolean(k, d)).apply()
        settings()
    }

    private fun settings() {
        val ctx = requireContext()
        val labels = arrayOf(
            "Tema (claro / oscuro)",
            "Tamaño del texto del editor",
            "Bloqueo con huella / PIN: ${onOff("lock", false)}",
            "Impedir capturas de pantalla: ${onOff("secure", false)}",
            "Copia de seguridad antes de guardar: ${onOff("backup", true)}",
            "Ver copias de seguridad…",
            "Privacidad y acerca de"
        )
        AlertDialog.Builder(ctx).setTitle("Ajustes").setItems(labels) { _, i ->
            when (i) {
                0 -> AlertDialog.Builder(ctx).setTitle("Tema")
                    .setSingleChoiceItems(arrayOf("Seguir al sistema", "Claro", "Oscuro"), prefs.getInt("theme", 0)) { d, w ->
                        prefs.edit().putInt("theme", w).apply()
                        AppCompatDelegate.setDefaultNightMode(
                            when (w) {
                                1 -> AppCompatDelegate.MODE_NIGHT_NO
                                2 -> AppCompatDelegate.MODE_NIGHT_YES
                                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            }
                        )
                        d.dismiss()
                    }.show()
                1 -> {
                    val sizes = intArrayOf(14, 16, 20, 24)
                    val cur = sizes.indexOf(prefs.getInt("textSize", 16)).coerceAtLeast(0)
                    AlertDialog.Builder(ctx).setTitle("Tamaño del texto")
                        .setSingleChoiceItems(sizes.map { "$it sp" }.toTypedArray(), cur) { d, w ->
                            prefs.edit().putInt("textSize", sizes[w]).apply(); d.dismiss()
                        }.show()
                }
                2 -> {
                    val can = BiometricManager.from(ctx).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    if (!prefs.getBoolean("lock", false) && can != BiometricManager.BIOMETRIC_SUCCESS)
                        toast("Configura primero un bloqueo de pantalla (huella, PIN o patrón) en el dispositivo")
                    else toggle("lock", false)
                }
                3 -> toggle("secure", false)
                4 -> toggle("backup", true)
                5 -> backups()
                else -> AlertDialog.Builder(ctx).setTitle("DocReader")
                    .setMessage(
                        "Lector y editor de documentos sin conexión: Word, Excel, PDF, texto, Markdown, CSV, JSON y HTML.\n\n" +
                            "Privacidad: todo se procesa en tu dispositivo. La app no tiene permiso de Internet ni envía datos a ningún servidor. " +
                            "Solo accede a los archivos que tú eliges. Los borradores y copias de seguridad se guardan en el almacenamiento privado de la app."
                    ).setPositiveButton("Cerrar", null).show()
            }
        }.show()
    }

    private fun backups() {
        val ctx = requireContext()
        val list: List<File> = Backups.list(ctx)
        if (list.isEmpty()) { toast("Todavía no hay copias de seguridad"); return }
        val labels = list.map {
            val ts = it.name.substringBefore('_')
            val name = it.name.substringAfter('_')
            "$name\n${ts.take(4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)} ${ts.substring(9, 11)}:${ts.substring(11, 13)}"
        }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Copias de seguridad")
            .setItems(labels) { _, i ->
                AlertDialog.Builder(ctx).setTitle("Abrir copia")
                    .setMessage("Se abrirá la copia. Para restaurarla, usa «Guardar como» y elige dónde.")
                    .setPositiveButton("Abrir") { _, _ -> openUri(Uri.fromFile(list[i]).toString()) }
                    .setNegativeButton("Cancelar", null).show()
            }.setNegativeButton("Cerrar", null).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
