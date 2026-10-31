package com.docreader.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.databinding.ItemJsonBinding
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.math.min

/** Árbol JSON editable: expandir/contraer, editar valores y tipos, añadir, renombrar, duplicar, mover y eliminar. */
class JsonTreeController(
    private val ctx: Context,
    private val rv: RecyclerView,
    root0: JsonElement,
    private val onChanged: (String) -> Unit
) {
    private class Row(
        val depth: Int, val label: String, val el: JsonElement, val parent: JsonElement?,
        val key: String?, val index: Int, val path: String, val more: Int = 0
    )

    var root: JsonElement = root0
        private set
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
    private val expanded = HashSet<String>().apply { add("$") }
    private val rows = ArrayList<Row>()
    private val undoS = ArrayDeque<String>()
    private val redoS = ArrayDeque<String>()
    private val adapter = A()

    init {
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter
        rebuild()
    }

    fun text(): String = gson.toJson(root)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ árbol visible
    private fun childPath(path: String, key: String) =
        if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(key)) "$path.$key" else "$path[\"${key.replace("\"", "\\\"")}\"]"

    private fun rebuild() {
        rows.clear()
        walk(root, null, null, -1, "$", 0, "raíz")
    }

    private fun walk(el: JsonElement, parent: JsonElement?, key: String?, index: Int, path: String, depth: Int, label: String) {
        rows.add(Row(depth, label, el, parent, key, index, path))
        if (path !in expanded) return
        if (el.isJsonObject) {
            var n = 0
            val obj = el.asJsonObject
            for ((k, v) in obj.entrySet()) {
                if (n++ >= MAX) { rows.add(Row(depth + 1, "…", JsonNull.INSTANCE, null, null, -1, "$path#more", obj.size() - MAX)); break }
                walk(v, el, k, -1, childPath(path, k), depth + 1, k)
            }
        } else if (el.isJsonArray) {
            val a = el.asJsonArray
            for (i in 0 until min(a.size(), MAX)) walk(a[i], el, null, i, "$path[$i]", depth + 1, "[$i]")
            if (a.size() > MAX) rows.add(Row(depth + 1, "…", JsonNull.INSTANCE, null, null, -1, "$path#more", a.size() - MAX))
        }
    }

    private fun refresh() {
        rebuild()
        adapter.notifyDataSetChanged()
        onChanged(text())
    }

    fun expandAll() {
        fun visit(el: JsonElement, path: String) {
            if (expanded.size > 5000) return
            if (el.isJsonObject) { expanded.add(path); for ((k, v) in el.asJsonObject.entrySet()) visit(v, childPath(path, k)) }
            else if (el.isJsonArray) { expanded.add(path); el.asJsonArray.forEachIndexed { i, v -> visit(v, "$path[$i]") } }
        }
        visit(root, "$")
        rebuild(); adapter.notifyDataSetChanged()
    }

    fun collapseAll() {
        expanded.clear(); expanded.add("$")
        rebuild(); adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------ deshacer
    private fun pushUndo() {
        undoS.addLast(gson.toJson(root))
        if (undoS.size > 30) undoS.removeFirst()
        redoS.clear()
    }

    fun undo() {
        if (undoS.isEmpty()) return
        redoS.addLast(gson.toJson(root))
        root = JsonParser.parseString(undoS.removeLast())
        refresh()
    }

    fun redo() {
        if (redoS.isEmpty()) return
        undoS.addLast(gson.toJson(root))
        root = JsonParser.parseString(redoS.removeLast())
        refresh()
    }

    private fun mutate(block: () -> Unit) { pushUndo(); block(); refresh() }

    // ------------------------------------------------------------ operaciones
    private fun replaceChild(r: Row, el: JsonElement) {
        val p = r.parent
        when {
            p == null -> root = el
            p.isJsonObject -> p.asJsonObject.add(r.key!!, el)
            p.isJsonArray -> p.asJsonArray.set(r.index, el)
        }
    }

    private fun insertAt(arr: JsonArray, idx: Int, el: JsonElement) {
        val tail = ArrayList<JsonElement>()
        while (arr.size() > idx) tail.add(arr.remove(idx))
        arr.add(el)
        tail.forEach { arr.add(it) }
    }

    private fun rename(obj: JsonObject, old: String, new: String) {
        val entries = obj.entrySet().map { it.key to it.value }
        for ((k, _) in entries) obj.remove(k)
        for ((k, v) in entries) obj.add(if (k == old) new else k, v)
    }

    private fun typeIndex(e: JsonElement?): Int = when {
        e == null || e.isJsonNull -> 3
        e.isJsonObject -> 4
        e.isJsonArray -> 5
        e.asJsonPrimitive.isBoolean -> 2
        e.asJsonPrimitive.isNumber -> 1
        else -> 0
    }

    /** Diálogo para crear/editar un valor (con clave opcional). */
    private fun valueDialog(title: String, needKey: Boolean, initKey: String, init: JsonElement?, onOk: (String, JsonElement) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val keyEt = EditText(ctx).apply { hint = "Clave"; setText(initKey); setSingleLine() }
        if (needKey) box.addView(keyEt)
        val types = arrayOf("Texto", "Número", "Booleano", "Nulo", "Objeto { }", "Lista [ ]")
        val sp = Spinner(ctx)
        sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, types)
        sp.setSelection(typeIndex(init))
        box.addView(sp)
        val et = EditText(ctx).apply {
            hint = "Valor"
            minLines = 1
            setText(if (init != null && init.isJsonPrimitive) init.asString else "")
        }
        box.addView(et)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                et.isEnabled = pos <= 2
                if (pos == 2 && et.text.toString() != "true" && et.text.toString() != "false") et.setText("true")
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val dlg = AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setPositiveButton("Aceptar", null).setNegativeButton("Cancelar", null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val txt = et.text.toString()
            val el: JsonElement? = when (sp.selectedItemPosition) {
                0 -> JsonPrimitive(txt)
                1 -> txt.trim().toBigDecimalOrNull()?.let { JsonPrimitive(it) }
                2 -> JsonPrimitive(txt.trim().equals("true", true))
                3 -> JsonNull.INSTANCE
                4 -> if (init != null && init.isJsonObject) init else JsonObject()
                else -> if (init != null && init.isJsonArray) init else JsonArray()
            }
            val k = keyEt.text.toString()
            when {
                el == null -> toast("Número no válido")
                needKey && k.isEmpty() -> toast("Escribe una clave")
                else -> { dlg.dismiss(); onOk(k, el) }
            }
        }
    }

    private fun editValue(r: Row) {
        valueDialog("Editar ${r.label}", false, "", r.el) { _, el -> mutate { replaceChild(r, el) } }
    }

    private fun addChild(r: Row) {
        val el = r.el
        if (el.isJsonObject) {
            valueDialog("Añadir a ${r.label}", true, "", JsonPrimitive("")) { k, v ->
                if (el.asJsonObject.has(k)) toast("Ya existe esa clave")
                else mutate { el.asJsonObject.add(k, v); expanded.add(r.path) }
            }
        } else if (el.isJsonArray) {
            valueDialog("Añadir elemento a ${r.label}", false, "", JsonPrimitive("")) { _, v ->
                mutate { el.asJsonArray.add(v); expanded.add(r.path) }
            }
        }
    }

    private fun menu(r: Row, anchor: View) {
        if (r.more > 0) return
        val container = r.el.isJsonObject || r.el.isJsonArray
        val parent = r.parent
        val items = ArrayList<Pair<String, String>>()
        if (container) items.add("Añadir…" to "add")
        if (r.path != "$") items.add("Editar / cambiar tipo" to "edit")
        else if (!container) items.add("Editar" to "edit")
        if (parent != null && parent.isJsonObject) items.add("Renombrar clave" to "rename")
        if (parent != null) items.add("Duplicar" to "dup")
        if (parent != null && parent.isJsonArray) { items.add("Mover arriba" to "up"); items.add("Mover abajo" to "down") }
        if (parent != null) items.add("Eliminar" to "del")
        items.add("Copiar valor" to "copy")
        items.add("Copiar ruta" to "path")
        val m = PopupMenu(ctx, anchor)
        items.forEachIndexed { i, o -> m.menu.add(0, i, i, o.first) }
        m.setOnMenuItemClickListener {
            when (items[it.itemId].second) {
                "add" -> addChild(r)
                "edit" -> editValue(r)
                "rename" -> {
                    val et = EditText(ctx).apply { setText(r.key); setSelection(text.length); setSingleLine() }
                    val box = LinearLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
                    AlertDialog.Builder(ctx).setTitle("Renombrar clave").setView(box)
                        .setPositiveButton("Aceptar") { _, _ ->
                            val nk = et.text.toString()
                            val obj = parent!!.asJsonObject
                            if (nk.isEmpty() || (nk != r.key && obj.has(nk))) toast("Clave no válida o repetida")
                            else mutate { rename(obj, r.key!!, nk) }
                        }.setNegativeButton("Cancelar", null).show()
                }
                "dup" -> mutate {
                    if (parent!!.isJsonObject) {
                        var nk = r.key + "_copia"
                        while (parent.asJsonObject.has(nk)) nk += "_"
                        parent.asJsonObject.add(nk, r.el.deepCopy())
                    } else insertAt(parent.asJsonArray, r.index + 1, r.el.deepCopy())
                }
                "up" -> if (r.index > 0) mutate {
                    val a = parent!!.asJsonArray
                    val t = a[r.index - 1]; a.set(r.index - 1, a[r.index]); a.set(r.index, t)
                }
                "down" -> if (r.index < parent!!.asJsonArray.size() - 1) mutate {
                    val a = parent.asJsonArray
                    val t = a[r.index + 1]; a.set(r.index + 1, a[r.index]); a.set(r.index, t)
                }
                "del" -> mutate {
                    if (parent!!.isJsonObject) parent.asJsonObject.remove(r.key!!) else parent.asJsonArray.remove(r.index)
                }
                "copy" -> clip(gson.toJson(r.el))
                "path" -> clip(r.path)
            }
            true
        }
        m.show()
    }

    private fun clip(t: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("JSON", t))
        toast("Copiado")
    }

    // ------------------------------------------------------------ adaptador
    private inner class A : RecyclerView.Adapter<A.VH>() {
        inner class VH(val b: ItemJsonBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemJsonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val container = r.el.isJsonObject || r.el.isJsonArray
            h.b.root.setPadding(dp(8 + r.depth * 16), dp(4), dp(8), dp(4))
            h.b.jArrow.text = if (r.more > 0 || !container) "" else if (r.path in expanded) "▾" else "▸"
            h.b.jKey.text = if (r.more > 0) "" else r.label + ":"
            val v = h.b.jVal
            when {
                r.more > 0 -> { v.text = "… ${r.more} más (usa el modo Texto para verlos)"; v.setTextColor(0xFF757575.toInt()) }
                r.el.isJsonObject -> { v.text = "{ }  ${r.el.asJsonObject.size()} claves"; v.setTextColor(0xFF757575.toInt()) }
                r.el.isJsonArray -> { v.text = "[ ]  ${r.el.asJsonArray.size()} elementos"; v.setTextColor(0xFF757575.toInt()) }
                r.el.isJsonNull -> { v.text = "null"; v.setTextColor(0xFF9E9E9E.toInt()) }
                r.el.asJsonPrimitive.isBoolean -> { v.text = r.el.asString; v.setTextColor(0xFFEF6C00.toInt()) }
                r.el.asJsonPrimitive.isNumber -> { v.text = r.el.asString; v.setTextColor(0xFF1E88E5.toInt()) }
                else -> { v.text = "\"" + r.el.asString + "\""; v.setTextColor(0xFF43A047.toInt()) }
            }
            h.b.root.setOnClickListener {
                when {
                    r.more > 0 -> Unit
                    container -> { if (!expanded.add(r.path)) expanded.remove(r.path); rebuild(); notifyDataSetChanged() }
                    else -> editValue(r)
                }
            }
            h.b.root.setOnLongClickListener { menu(r, it); true }
        }
    }

    companion object { private const val MAX = 1000 }
}
