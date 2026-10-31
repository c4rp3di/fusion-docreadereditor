package com.docreader.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.docreader.editor.databinding.FragmentViewerBinding
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.jsoup.Jsoup
import kotlin.math.max
import kotlin.math.min

/**
 * Editor de formatos de texto: deshacer/rehacer, buscar y reemplazar y, según el tipo,
 * barra de Markdown (con vista previa), árbol JSON editable o vista previa HTML.
 */
class TextEditorController(
    private val ctx: Context,
    private val b: FragmentViewerBinding,
    private val vm: ViewerViewModel,
    private val ext: String,
    private val editable: Boolean
) {
    enum class Mode { EDIT, PREVIEW, TREE }

    private val tb get() = b.textBar
    private val isMd = ext == "md" || ext == "markdown"
    private val isJson = ext == "json"
    private val isHtml = ext == "html" || ext == "htm"
    private var mode = Mode.EDIT
    private val undoS = ArrayDeque<String>()
    private val redoS = ArrayDeque<String>()
    private var lastEdit = 0L
    private var silent = false
    private var tree: JsonTreeController? = null
    private val gsonPretty = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
    private val gsonCompact = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private var findFrom = 0
    private var baseline = vm.draft

    fun shortcut(code: Int): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_Z -> { undo(); true }
        android.view.KeyEvent.KEYCODE_Y -> { redo(); true }
        android.view.KeyEvent.KEYCODE_F -> { if (mode == Mode.EDIT) findDialog(); true }
        else -> false
    }

    fun dirty() = vm.draft != baseline
    fun markSaved() { baseline = vm.draft }

    fun isEditMode() = mode == Mode.EDIT

    private val markwon by lazy {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(ctx))
            .usePlugin(TaskListPlugin.create(ctx))
            .build()
    }

    init {
        b.editor.setText(vm.draft)
        b.editor.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt("textSize", 16).toFloat()
        )
        if (!editable) {
            b.editor.keyListener = null
            b.editor.setTextIsSelectable(true)
        }
        b.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                if (silent) return
                val t = SystemClock.uptimeMillis()
                if (t - lastEdit > 1000) pushUndo(vm.draft)
                lastEdit = t
            }
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { if (!silent) vm.draft = e?.toString().orEmpty() }
        })
        tb.grpMd.isVisible = isMd
        tb.grpJson.isVisible = isJson
        tb.grpHtml.isVisible = isHtml
        bindButtons()
        if (isHtml) setupWebView()
        if (isJson) {
            if (!tryTree()) toast("JSON no válido: corrígelo en el modo Texto")
        }
        setMode(mode)
    }

    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ modos
    private fun setMode(m: Mode) {
        mode = m
        b.textScroll.isVisible = m == Mode.EDIT
        b.previewScroll.isVisible = m == Mode.PREVIEW && isMd
        b.htmlView.isVisible = m == Mode.PREVIEW && isHtml
        b.jsonTree.isVisible = m == Mode.TREE
        tb.grpMdFmt.isVisible = isMd && m == Mode.EDIT
        tb.tmPreview.text = if (m == Mode.PREVIEW) "Editar" else "Vista previa"
        tb.thPreview.text = if (m == Mode.PREVIEW) "Código" else "Vista previa"
        tb.tjView.text = if (m == Mode.TREE) "Texto" else "Árbol"
        tb.tjExpand.isVisible = m == Mode.TREE
        tb.tjCollapse.isVisible = m == Mode.TREE
        tb.tjFormat.isVisible = m == Mode.EDIT
        tb.tjMinify.isVisible = m == Mode.EDIT
        tb.ttFind.isVisible = m == Mode.EDIT
    }

    private fun setupWebView() {
        val s = b.htmlView.settings
        s.javaScriptEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.blockNetworkLoads = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
    }

    private fun togglePreview() {
        if (mode == Mode.PREVIEW) { setMode(Mode.EDIT); return }
        if (isMd) markwon.setMarkdown(b.preview, vm.draft)
        if (isHtml) (b.htmlView as WebView).loadDataWithBaseURL(null, vm.draft, "text/html", "UTF-8", null)
        setMode(Mode.PREVIEW)
    }

    /** Intenta pasar del texto al árbol JSON; false si el texto no es JSON válido. */
    private fun tryTree(): Boolean {
        return try {
            val el = if (vm.draft.isBlank()) JsonObject() else JsonParser.parseString(vm.draft)
            tree = JsonTreeController(ctx, b.jsonTree, el) { t -> vm.draft = t }
            mode = Mode.TREE
            true
        } catch (e: Exception) {
            JsonErr.last = e.message?.lineSequence()?.firstOrNull()
            mode = Mode.EDIT
            false
        }
    }

    private fun toggleJsonView() {
        if (mode == Mode.TREE) {
            silent = true; b.editor.setText(vm.draft); silent = false
            setMode(Mode.EDIT)
        } else {
            if (tryTree()) setMode(Mode.TREE)
            else toast("JSON no válido: ${(JsonErr.last ?: "revisa la sintaxis").take(120)}")
        }
    }

    private object JsonErr { var last: String? = null }

    // ------------------------------------------------------------ deshacer / rehacer
    private fun pushUndo(t: String) {
        if (undoS.lastOrNull() == t) return
        undoS.addLast(t)
        if (undoS.size > 50) undoS.removeFirst()
        redoS.clear()
    }

    private fun checkpoint() { pushUndo(vm.draft); lastEdit = SystemClock.uptimeMillis() }

    private fun applyText(t: String) {
        silent = true
        val sel = b.editor.selectionStart
        b.editor.setText(t)
        vm.draft = t
        silent = false
        b.editor.setSelection(min(max(sel, 0), t.length))
    }

    private fun undo() {
        if (mode == Mode.TREE) { tree?.undo(); return }
        if (undoS.isEmpty()) return
        redoS.addLast(vm.draft)
        applyText(undoS.removeLast())
    }

    private fun redo() {
        if (mode == Mode.TREE) { tree?.redo(); return }
        if (redoS.isEmpty()) return
        undoS.addLast(vm.draft)
        applyText(redoS.removeLast())
    }

    // ------------------------------------------------------------ botones
    private fun bindButtons() {
        tb.ttUndo.setOnClickListener { undo() }
        tb.ttRedo.setOnClickListener { redo() }
        tb.ttFind.setOnClickListener { findDialog() }
        // Markdown
        tb.tmPreview.setOnClickListener { togglePreview() }
        tb.tmBold.setOnClickListener { wrap("**") }
        tb.tmItalic.setOnClickListener { wrap("*") }
        tb.tmStrike.setOnClickListener { wrap("~~") }
        tb.tmCode.setOnClickListener { code() }
        tb.tmHeading.setOnClickListener { heading() }
        tb.tmList.setOnClickListener { prefixLines({ "- " }, Regex("^\\s*[-*+] (\\[[ xX]\\] )?")) }
        tb.tmNum.setOnClickListener { prefixLines({ "${it + 1}. " }, Regex("^\\s*\\d+\\. ")) }
        tb.tmTask.setOnClickListener { prefixLines({ "- [ ] " }, Regex("^\\s*[-*+] \\[[ xX]\\] ")) }
        tb.tmQuote.setOnClickListener { prefixLines({ "> " }, Regex("^> ?")) }
        tb.tmLink.setOnClickListener { link() }
        tb.tmTable.setOnClickListener { insert("\n| Columna 1 | Columna 2 |\n|---|---|\n|  |  |\n") }
        tb.tmHr.setOnClickListener { insert("\n---\n") }
        // JSON
        tb.tjView.setOnClickListener { toggleJsonView() }
        tb.tjExpand.setOnClickListener { tree?.expandAll() }
        tb.tjCollapse.setOnClickListener { tree?.collapseAll() }
        tb.tjFormat.setOnClickListener { reformatJson(true) }
        tb.tjMinify.setOnClickListener { reformatJson(false) }
        tb.tjCopy.setOnClickListener {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("JSON", vm.draft))
            toast("JSON copiado")
        }
        // HTML
        tb.thPreview.setOnClickListener { togglePreview() }
        tb.thFormat.setOnClickListener {
            try { checkpoint(); applyText(Jsoup.parse(vm.draft).outerHtml()) } catch (e: Exception) { toast("No se pudo formatear") }
        }
    }

    private fun reformatJson(pretty: Boolean) {
        try {
            val el = JsonParser.parseString(vm.draft)
            checkpoint()
            applyText((if (pretty) gsonPretty else gsonCompact).toJson(el))
        } catch (e: Exception) {
            toast("JSON no válido: ${(e.message ?: "").lineSequence().firstOrNull()?.take(100) ?: ""}")
        }
    }

    // ------------------------------------------------------------ edición Markdown
    private fun sel(): Pair<Int, Int> {
        val s = b.editor.selectionStart.coerceAtLeast(0)
        val e = b.editor.selectionEnd.coerceAtLeast(0)
        return min(s, e) to max(s, e)
    }

    private fun wrap(pre: String, suf: String = pre, ph: String = "texto") {
        val e = b.editor.editableText
        val (s, en) = sel()
        val sel = e.subSequence(s, en).toString()
        checkpoint()
        if (sel.length >= pre.length + suf.length && sel.startsWith(pre) && sel.endsWith(suf)) {
            e.replace(s, en, sel.substring(pre.length, sel.length - suf.length))
            b.editor.setSelection(s, en - pre.length - suf.length)
        } else {
            val inner = sel.ifEmpty { ph }
            e.replace(s, en, pre + inner + suf)
            b.editor.setSelection(s + pre.length, s + pre.length + inner.length)
        }
    }

    private fun code() {
        val (s, en) = sel()
        val txt = b.editor.editableText.subSequence(s, en).toString()
        if (txt.contains('\n')) wrap("```\n", "\n```", "código") else wrap("`", "`", "código")
    }

    private fun heading() {
        val e = b.editor.editableText
        val t = e.toString()
        val (s, _) = sel()
        val a = t.lastIndexOf('\n', s - 1).let { if (it < 0) 0 else it + 1 }
        val z = t.indexOf('\n', a).let { if (it < 0) t.length else it }
        val line = t.substring(a, z)
        val m = Regex("^(#{1,6}) ").find(line)
        val n = m?.groupValues?.get(1)?.length ?: 0
        val bare = line.removePrefix(m?.value ?: "")
        val nl = if (n >= 3) bare else "#".repeat(n + 1) + " " + bare
        checkpoint()
        e.replace(a, z, nl)
        b.editor.setSelection(a + nl.length)
    }

    private fun prefixLines(prefix: (Int) -> String, strip: Regex) {
        val e = b.editor.editableText
        val t = e.toString()
        val (s, en) = sel()
        val a = t.lastIndexOf('\n', s - 1).let { if (it < 0) 0 else it + 1 }
        val z = t.indexOf('\n', en).let { if (it < 0) t.length else it }
        val lines = t.substring(a, z).split('\n')
        val allHave = lines.all { strip.containsMatchIn(it) }
        val out = lines.mapIndexed { i, l ->
            val bare = strip.replace(l, "")
            if (allHave) bare else prefix(i) + bare
        }.joinToString("\n")
        checkpoint()
        e.replace(a, z, out)
        b.editor.setSelection(a, a + out.length)
    }

    private fun link() {
        val e = b.editor.editableText
        val (s, en) = sel()
        val txt = e.subSequence(s, en).toString().ifEmpty { "texto" }
        checkpoint()
        e.replace(s, en, "[$txt](https://)")
        val urlStart = s + txt.length + 3
        b.editor.setSelection(urlStart, urlStart + 8)
    }

    private fun insert(t: String) {
        val e = b.editor.editableText
        val (s, en) = sel()
        checkpoint()
        e.replace(s, en, t)
        b.editor.setSelection(s + t.length)
    }

    // ------------------------------------------------------------ buscar y reemplazar
    private fun findDialog() {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val f = EditText(ctx).apply { hint = "Buscar"; setSingleLine() }
        val r = EditText(ctx).apply { hint = "Reemplazar con"; setSingleLine() }
        val cs = CheckBox(ctx).apply { text = "Distinguir mayúsculas" }
        box.addView(f); box.addView(r); box.addView(cs)
        val dlg = AlertDialog.Builder(ctx).setTitle("Buscar y reemplazar").setView(box)
            .setPositiveButton("Siguiente", null).setNeutralButton("Reemplazar", null).setNegativeButton("Todo", null).create()
        dlg.show()
        dlg.window?.setDimAmount(0f)
        dlg.window?.setGravity(Gravity.BOTTOM)
        findFrom = b.editor.selectionEnd.coerceAtLeast(0)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { next(f.text.toString(), cs.isChecked) }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val q = f.text.toString()
            val (s, en) = sel()
            if (q.isNotEmpty() && en > s && b.editor.text.substring(s, en).equals(q, ignoreCase = !cs.isChecked)) {
                checkpoint()
                b.editor.editableText.replace(s, en, r.text.toString())
                findFrom = s + r.text.length
            }
            next(q, cs.isChecked)
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val q = f.text.toString()
            if (q.isEmpty()) return@setOnClickListener
            val t = vm.draft
            val n = Regex(Regex.escape(q), if (cs.isChecked) emptySet() else setOf(RegexOption.IGNORE_CASE)).findAll(t).count()
            if (n > 0) {
                checkpoint()
                applyText(t.replace(q, r.text.toString(), ignoreCase = !cs.isChecked))
            }
            toast("$n reemplazos")
        }
    }

    private fun next(q: String, matchCase: Boolean) {
        if (q.isEmpty()) return
        val t = b.editor.text.toString()
        var i = t.indexOf(q, findFrom.coerceAtMost(t.length), ignoreCase = !matchCase)
        if (i < 0) i = t.indexOf(q, 0, ignoreCase = !matchCase)
        if (i < 0) { toast("No se encontró"); return }
        b.editor.requestFocus()
        b.editor.setSelection(i, i + q.length)
        findFrom = i + q.length
    }
}
