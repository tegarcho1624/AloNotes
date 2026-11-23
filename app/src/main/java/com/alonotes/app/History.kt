package com.alonotes.app

import java.io.File

/** Estado lógico de una imagen en una hoja (sin el bitmap: solo el archivo y sus propiedades). */
internal class ItemSnap(
    val id: String,
    val file: File,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val rot: Float,
    val baseW: Float,
    val fh: Boolean,
    val fv: Boolean,
    val op: Float,
    val lt: Int,
    val ld: String,
    val lk: Boolean
)

/** Estado lógico de una capa. Los trazos son inmutables, así que se guardan las referencias (no se copian los puntos). */
internal class LayerSnap(
    val id: Long,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val strokes: List<Stroke>,
    val items: List<ItemSnap>
)

/** Estado lógico de una hoja: sus capas con trazos e imágenes, la capa seleccionada y el contador de nombres. */
internal class PageSnap(val layers: List<LayerSnap>, val selected: Int, val seq: Int)

/**
 * Historial de deshacer/rehacer de la hoja actual: dos pilas de PageSnap (el estado de ANTES de cada acción), con un máximo
 * de [limit] pasos. Una acción nueva borra la pila de rehacer. Se vacía al cambiar de hoja (y al salir de la nota, porque
 * vive en la pantalla del editor).
 */
internal class PageHistory(private val limit: Int = 30) {
    private val undoStack = ArrayDeque<PageSnap>()
    private val redoStack = ArrayDeque<PageSnap>()

    /** Se llama cada vez que cambia lo que se puede deshacer o rehacer (para activar o apagar los botones). */
    var onChange: (() -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Guarda el estado de ANTES de una acción que ya se completó. */
    fun record(before: PageSnap) {
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        onChange?.invoke()
    }

    /** Devuelve el estado anterior y guarda [current] para poder rehacer; null si no hay nada que deshacer. */
    fun undo(current: PageSnap): PageSnap? {
        val s = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        onChange?.invoke()
        return s
    }

    /** Devuelve el estado que se deshizo y guarda [current] para poder deshacer otra vez; null si no hay nada que rehacer. */
    fun redo(current: PageSnap): PageSnap? {
        val s = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        onChange?.invoke()
        return s
    }

    fun clear() {
        if (undoStack.isEmpty() && redoStack.isEmpty()) return
        undoStack.clear()
        redoStack.clear()
        onChange?.invoke()
    }
}
