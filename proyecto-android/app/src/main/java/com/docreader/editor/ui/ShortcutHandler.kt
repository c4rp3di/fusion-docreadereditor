package com.docreader.editor.ui

/** Fragmentos que reaccionan a atajos de teclado Ctrl+tecla (teclado físico). */
interface ShortcutHandler {
    fun onShortcut(keyCode: Int): Boolean
}
